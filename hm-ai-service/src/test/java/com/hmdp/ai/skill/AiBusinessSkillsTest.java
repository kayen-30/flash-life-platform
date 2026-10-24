package com.hmdp.ai.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.agent.CustomerAgent;
import com.hmdp.ai.agent.log.AgentExecutionLogService;
import com.hmdp.ai.agent.tool.AiToolCallBudget;
import com.hmdp.ai.execution.*;
import com.hmdp.ai.security.AiPrivacySanitizer;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.skills.ClassPathSkillLoader;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AiBusinessSkillsTest {
    private final AiBusinessSkills skills = new AiBusinessSkills(true);
    private final AiToolCallBudget toolBudget = new AiToolCallBudget();
    private final AgentExecutionLogService log = new AgentExecutionLogService(
            mock(StringRedisTemplate.class), new ObjectMapper(), new AiPrivacySanitizer());

    @Test
    void ordinaryVoucherQueryNeedsNoActivationAndInlineUsesTheSameBody() {
        AtomicInteger rounds = new AtomicInteger();
        AtomicInteger actual = new AtomicInteger();
        ChatModel model = new ChatModel() {
            public ChatResponse doChat(ChatRequest request) {
                if (rounds.incrementAndGet() == 1) {
                    assertThat(request.toolSpecifications()).extracting(ToolSpecification::name)
                            .contains("query_shop_vouchers").doesNotContain("inspect_voucher_conditions");
                    return call("query_shop_vouchers", "{\"shopName\":\"测试店\"}");
                }
                return answer("查券完成");
            }
        };
        try (var ignored = skills.open()) { agent(skills, model, actual).chat("查测试店的券"); }
        assertThat(rounds).hasValue(2);
        assertThat(actual).hasValue(1);
        var inline = new AiBusinessSkills(true, "inline");
        try (var ignored = inline.open()) {
            assertThat(inline.catalog()).contains(ClassPathSkillLoader.loadSkill("skills/voucher-selection").content())
                    .doesNotContain("<available_skills>");
            assertThat(names(inline.toolProvider(available(actual), toolBudget, log)))
                    .contains("inspect_voucher_conditions").doesNotContain("activate_skill");
        }
    }

    @Test
    void officialClasspathLoaderProvidesCatalogueWithoutEagerBodyInjection() {
        var loaded = ClassPathSkillLoader.loadSkill("skills/voucher-selection");
        assertThat(loaded.name()).isEqualTo("voucher-selection");
        assertThat(loaded.content()).contains("区分买券预算和用餐预算");
        assertThat(skills.catalog()).contains("<available_skills>", "voucher-selection", "activate_skill")
                .doesNotContain("## 同店流程", "区分买券预算和用餐预算");
        var unapproved = available(new AtomicInteger());
        unapproved.put(spec("run_shell_command"), (request, id) -> "bad");
        assertThatThrownBy(() -> skills.toolProvider(unapproved, toolBudget, log)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void realOfficialActivationUnlocksToolsOnNextRoundAndCountsEveryStep() {
        AtomicInteger rounds = new AtomicInteger();
        AtomicInteger actualTools = new AtomicInteger();
        ChatModel delegate = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                List<String> catalog = request.toolSpecifications().stream().map(ToolSpecification::name).toList();
                int round = rounds.incrementAndGet();
                if (round == 1) {
                    assertThat(catalog).contains("activate_skill", "query_shop_by_name", "query_shop_blogs")
                            .doesNotContain("inspect_voucher_conditions");
                    assertThat(((SystemMessage) request.messages().get(0)).text()).contains("<available_skills>")
                            .doesNotContain("区分买券预算和用餐预算");
                    return call("activate_skill", "{\"skill_name\":\"voucher-selection\"}");
                }
                assertThat(catalog).contains("query_shop_vouchers", "query_shop_blogs", "inspect_voucher_conditions");
                assertThat(request.messages()).anySatisfy(message -> {
                    assertThat(message).isInstanceOf(ToolExecutionResultMessage.class);
                    var result = (ToolExecutionResultMessage) message;
                    assertThat(result.attributes()).containsEntry("activated_skill", "voucher-selection");
                    assertThat(result.text()).contains("区分买券预算和用餐预算");
                });
                if (round == 2) return call("query_shop_by_name", "{\"name\":\"测试店\"}");
                if (round == 3) return call("query_shop_vouchers", "{\"shopId\":1}");
                return answer("已查询真实优惠券，库存未知。");
            }
        };
        AiExecutionMetrics metrics = new AiExecutionMetrics(new SimpleMeterRegistry());
        var model = new BudgetedChatModel(delegate, metrics, Duration.ofMillis(100), 512);
        CustomerAgent agent = agent(skills, model, actualTools);
        AiExecutionBudget budget = new AiExecutionGuard(new AiExecutionProperties(), metrics).newBudget("chat");
        budget.call(() -> {
            try (var ignored = skills.open()) {
                assertThat(agent.chat("查询测试店的优惠券").content()).contains("库存未知");
            }
            return null;
        });
        assertThat(actualTools).hasValue(2);
        assertThat(rounds).hasValue(4);
        assertThat(budget.getModelCalls()).isEqualTo(4);
        assertThat(budget.getRemainingToolCalls()).isEqualTo(1);
        assertThat(AiExecutionBudget.current()).isNull();
    }

    @Test
    void complexFlowCanActivateThenReadCombinedVoucherTool() {
        AtomicInteger rounds = new AtomicInteger();
        AtomicInteger actual = new AtomicInteger();
        ChatModel model = new ChatModel() {
            public ChatResponse doChat(ChatRequest request) {
                int round = rounds.incrementAndGet();
                if (round == 1) {
                    assertThat(request.toolSpecifications()).extracting(ToolSpecification::name).doesNotContain("inspect_voucher_conditions");
                    return call("activate_skill", "{\"skill_name\":\"voucher-selection\"}");
                }
                if (round == 2) return call("query_shop_vouchers", "{\"shopName\":\"测试店\"}");
                if (round == 3) return call("inspect_voucher_conditions", "{}");
                return answer("库存未知。");
            }
        };
        try (var ignored = skills.open()) { assertThat(agent(skills, model, actual).chat("查测试店优惠券").content()).contains("库存未知"); }
        assertThat(rounds).hasValue(4);
        assertThat(actual).hasValue(2);
    }

    @Test
    void disabledSkillsRetainOriginalBusinessToolsAndDoNotExposeActivation() {
        AiBusinessSkills disabled = new AiBusinessSkills(false);
        ToolProvider provider = disabled.toolProvider(available(new AtomicInteger()), toolBudget, log);
        try (var ignored = disabled.open()) {
            assertThat(disabled.catalog()).isEmpty();
            assertThat(names(provider)).containsExactlyInAnyOrder("query_shop_by_name", "query_shop_by_id", "query_shop_vouchers", "query_hot_blogs", "query_shop_blogs", "inspect_voucher_conditions");
            assertThat(disabled.requiresActivation("query_shop_vouchers")).isFalse();
        }
    }

    @Test
    void forgedEarlyToolFailsBeforeBusinessCodeAndScopeRestoresAfterError() {
        AtomicInteger actual = new AtomicInteger();
        assertThatThrownBy(() -> {
            try (var ignored = skills.open()) {
                toolBudget.executeTool(request("inspect_voucher_conditions", "{}"), null,
                        (r, id) -> { actual.incrementAndGet(); return "bad"; });
            }
        }).isInstanceOf(IllegalArgumentException.class);
        assertThat(actual).hasValue(0);
        assertThatCode(() -> AiBusinessSkills.requireToolAllowed("inspect_voucher_conditions")).doesNotThrowAnyException();
    }

    @Test
    void unknownSkillDoesNotUnlockVoucherTool() {
        ToolProvider provider = skills.toolProvider(available(new AtomicInteger()), toolBudget, log);
        try (var ignored = skills.open()) {
            var result = provider.provideTools(providerRequest());
            var activate = result.aiServiceTools().stream().filter(tool -> tool.name().equals("activate_skill")).findFirst().orElseThrow();
            assertThatThrownBy(() -> activate.toolExecutor().executeWithContext(
                    request("activate_skill", "{\"skill_name\":\"unknown\"}"), null)).isInstanceOf(RuntimeException.class);
            assertThat(names(provider)).doesNotContain("inspect_voucher_conditions");
            assertThatThrownBy(() -> AiBusinessSkills.requireToolAllowed("inspect_voucher_conditions")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void activationIsNotPersistedAcrossAgentInvocations() {
        AtomicInteger rounds = new AtomicInteger();
        ChatModel model = new ChatModel() {
            public ChatResponse doChat(ChatRequest request) {
                if (rounds.incrementAndGet() % 2 == 1) {
                    assertThat(request.toolSpecifications()).extracting(ToolSpecification::name).doesNotContain("inspect_voucher_conditions");
                    return call("activate_skill", "{\"skill_name\":\"voucher-selection\"}");
                }
                assertThat(request.toolSpecifications()).extracting(ToolSpecification::name).contains("inspect_voucher_conditions");
                return answer("请提供店名。");
            }
        };
        var agent = agent(skills, model, new AtomicInteger());
        for (int i = 0; i < 2; i++) {
            try (var ignored = skills.open()) { agent.chat("优惠券咨询"); }
        }
        assertThat(rounds).hasValue(4);
    }

    @Test
    void nestedAndConcurrentScopesDoNotLeakToolAuthorization() throws Exception {
        AiBusinessSkills disabled = new AiBusinessSkills(false);
        try (var outer = skills.open()) {
            try (var inner = disabled.open()) {
                assertThatCode(() -> AiBusinessSkills.requireToolAllowed("inspect_voucher_conditions")).doesNotThrowAnyException();
            }
            assertThatThrownBy(() -> AiBusinessSkills.requireToolAllowed("inspect_voucher_conditions")).isInstanceOf(IllegalArgumentException.class);
        }
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch active = new CountDownLatch(2);
        try {
            var restricted = executor.submit(() -> {
                try (var ignored = skills.open()) {
                    active.countDown(); active.await(5, TimeUnit.SECONDS);
                    assertThatThrownBy(() -> AiBusinessSkills.requireToolAllowed("inspect_voucher_conditions")).isInstanceOf(IllegalArgumentException.class);
                    return true;
                }
            });
            var unrestricted = executor.submit(() -> {
                try (var ignored = disabled.open()) {
                    active.countDown(); active.await(5, TimeUnit.SECONDS);
                    AiBusinessSkills.requireToolAllowed("inspect_voucher_conditions");
                    return true;
                }
            });
            assertThat(restricted.get(6, TimeUnit.SECONDS)).isTrue();
            assertThat(unrestricted.get(6, TimeUnit.SECONDS)).isTrue();
        } finally { executor.shutdownNow(); }
    }

    private CustomerAgent agent(AiBusinessSkills skills, ChatModel model, AtomicInteger actual) {
        return AiServices.builder(CustomerAgent.class).chatModel(model)
                .systemMessageTransformer(system -> system + skills.catalog())
                .toolProvider(skills.toolProvider(available(actual), toolBudget, log)).build();
    }

    private Map<ToolSpecification, ToolExecutor> available(AtomicInteger actual) {
        Map<ToolSpecification, ToolExecutor> result = new LinkedHashMap<>();
        for (String name : List.of("query_shop_by_name", "query_shop_by_id", "query_shop_vouchers", "query_hot_blogs", "query_shop_blogs", "inspect_voucher_conditions")) {
            result.put(spec(name), (request, id) -> toolBudget.executeTool(request, id,
                    (r, memory) -> { actual.incrementAndGet(); return "真实测试工具结果"; }));
        }
        return result;
    }

    private ToolSpecification spec(String name) { return ToolSpecification.builder().name(name).build(); }
    private ToolProviderRequest providerRequest() {
        return ToolProviderRequest.builder().invocationContext(dev.langchain4j.invocation.InvocationContext.builder().build())
                .userMessage(UserMessage.from("咨询"))
                .messages(List.of(UserMessage.from("咨询"))).build();
    }
    private List<String> names(ToolProvider provider) {
        return provider.provideTools(providerRequest()).aiServiceTools().stream().map(tool -> tool.name()).toList();
    }
    private static ToolExecutionRequest request(String name, String arguments) {
        return ToolExecutionRequest.builder().id("test-" + name).name(name).arguments(arguments).build();
    }
    private static ChatResponse call(String name, String arguments) {
        return ChatResponse.builder().aiMessage(AiMessage.from(request(name, arguments))).tokenUsage(new TokenUsage(100, 50)).build();
    }
    private static ChatResponse answer(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).tokenUsage(new TokenUsage(100, 50)).build();
    }
}
