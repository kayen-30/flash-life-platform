package com.hmdp.ai.agent.tool;

import com.hmdp.ai.agent.CustomerAgent;
import com.hmdp.ai.execution.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.AiServices;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class AiToolCallBudgetTest {
    @Test
    void productionClientDisablesThinkingExplicitlyWithoutIncreasingOutputLimit() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var received = new java.util.concurrent.atomic.AtomicReference<String>();
        server.createContext("/v1/chat/completions", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            byte[] response = "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1,\"total_tokens\":3}}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        try {
            var config = new com.hmdp.ai.config.LangChain4jConfig();
            org.springframework.test.util.ReflectionTestUtils.setField(config, "apiKey", "test-key");
            org.springframework.test.util.ReflectionTestUtils.setField(config, "baseUrl", "http://127.0.0.1:"+server.getAddress().getPort()+"/v1");
            org.springframework.test.util.ReflectionTestUtils.setField(config, "modelName", "cn:deepseek-v4.1-flash");
            org.springframework.test.util.ReflectionTestUtils.setField(config, "timeout", Duration.ofSeconds(2));
            org.springframework.test.util.ReflectionTestUtils.setField(config, "maxTokens", 512);
            org.springframework.test.util.ReflectionTestUtils.setField(config, "enableThinking", false);
            assertThat(config.chatLanguageModel(metrics).chat("返回ok")).isEqualTo("ok");
            var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(received.get());
            assertThat(body.path("thinking").path("type").asText()).isEqualTo("disabled");
            assertThat(body.path("max_tokens").asInt()).isEqualTo(512);
            assertThat(body.path("model").asText()).isEqualTo("cn:deepseek-v4.1-flash");
        } finally { server.stop(0); }
    }

    private final AiToolCallBudget tools = new AiToolCallBudget();
    private final AiExecutionMetrics metrics = new AiExecutionMetrics(new SimpleMeterRegistry());

    @Test
    void toolsOnlyConsumeToolCountNotDuplicateModelReserve() {
        AiExecutionBudget budget = budget(2, 5000, Duration.ofSeconds(25));
        tools.executeWithBudget(budget, () -> {
            tools.executeTool(request(), null, (r, id) -> "one");
            tools.executeTool(request(), null, (r, id) -> "two");
            return null;
        });
        assertThat(budget.getRemainingTokens()).isEqualTo(5000);
        assertThat(budget.getRemainingToolCalls()).isZero();
        assertThat(AiExecutionBudget.current()).isNull();
    }

    @Test
    void toolLimitAndDeadlineRejectBeforeDelegate() {
        AtomicInteger calls = new AtomicInteger();
        AiExecutionBudget budget = budget(1, 5000, Duration.ofSeconds(25));
        assertThatThrownBy(() -> budget.call(() -> {
            tools.executeTool(request(), null, (r,id) -> { calls.incrementAndGet(); return "ok"; });
            return tools.executeTool(request(), null, (r,id) -> { calls.incrementAndGet(); return "bad"; });
        })).isInstanceOf(AiToolCallBudgetExceededException.class);
        assertThat(calls).hasValue(1);
        assertThatThrownBy(() -> budget(1, 5000, Duration.ofMillis(1)).call(() ->
                tools.executeTool(request(), null, (r,id) -> { calls.incrementAndGet(); return "bad"; })))
                .isInstanceOf(AiDeadlineExceededException.class);
        assertThat(calls).hasValue(1);
        assertThat(AiExecutionBudget.current()).isNull();
    }

    @Test
    void allModelOverloadsSettleActualUsageAndEnforceRoundLimit() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel delegate = new ChatModel() {
            public ChatResponse doChat(ChatRequest request) {
                calls.incrementAndGet();
                return ChatResponse.builder().aiMessage(AiMessage.from("ok")).tokenUsage(new TokenUsage(70, 30)).build();
            }
        };
        BudgetedChatModel model = new BudgetedChatModel(delegate, metrics, Duration.ofSeconds(8), 512);
        AiExecutionBudget budget = budget(4, 5000, Duration.ofSeconds(25));
        List<ChatMessage> messages = List.of(UserMessage.from("test"));
        ToolSpecification spec = ToolSpecification.builder().name("read_shop").description("read").build();
        budget.call(() -> {
            model.chat(messages);
            model.chat(ChatRequest.builder().messages(messages).toolSpecifications(List.of(spec)).build());
            model.chat(ChatRequest.builder().messages(messages).toolSpecifications(spec).build());
            return null;
        });
        assertThat(calls).hasValue(3);
        assertThat(budget.getRemainingTokens()).isEqualTo(4700);
        assertThatThrownBy(() -> budget.call(() -> model.chat(messages)))
                .isInstanceOf(AiToolCallBudgetExceededException.class);
        assertThat(calls).hasValue(3);
    }

    @Test
    void unknownUsageRetainsReservationAndFailureClearsThreadContext() {
        ChatModel delegate = new ChatModel() {
            public ChatResponse doChat(ChatRequest request) { throw new IllegalStateException("provider unavailable"); }
        };
        BudgetedChatModel model = new BudgetedChatModel(delegate, metrics, Duration.ofSeconds(8), 512);
        AiExecutionBudget budget = budget(4, 5000, Duration.ofSeconds(25));
        assertThatThrownBy(() -> budget.call(() -> model.chat("hello")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(budget.getRemainingTokens()).isLessThan(5000);
        assertThat(AiExecutionBudget.current()).isNull();
    }

    @Test
    void evidenceEnvelopeDoesNotRequireProviderJsonMode() {
        ChatModel delegate = new ChatModel() {
            public ChatResponse doChat(ChatRequest request) {
                return ChatResponse.builder().aiMessage(AiMessage.from(
                        "{\"kind\":\"FAQ\",\"answer\":\"\",\"sourceIds\":[\"voucher-rule\",\"voucher-rule\"]}")).build();
            }
        };
        CustomerAgent agent = AiServices.create(CustomerAgent.class, delegate);
        CustomerAgent.Answer answer = CustomerAgent.parseAnswer(agent.chat("hello").content());
        assertThat(answer.answer()).isEmpty();
        assertThat(answer.sourceIds()).containsExactly("voucher-rule");
        assertThat(answer.kind()).isEqualTo(CustomerAgent.Kind.FAQ);
    }

    @Test
    void realHttpClientTimeoutStopsCallWithoutHiddenRetry() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger received = new AtomicInteger();
        server.createContext("/v1/chat/completions", exchange -> {
            received.incrementAndGet();
            try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            ChatModel delegate = dev.langchain4j.model.openai.OpenAiChatModel.builder()
                    .apiKey("local-test-only").modelName("test")
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                    .timeout(Duration.ofMillis(250)).maxRetries(0).build();
            long started = System.nanoTime();
            assertThatThrownBy(() -> delegate.chat("timeout probe")).isInstanceOf(RuntimeException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
            assertThat(received).hasValue(1);
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    private AiExecutionBudget budget(int tools, int tokens, Duration timeout) {
        AiExecutionProperties properties = new AiExecutionProperties();
        properties.setMaxToolCalls(tools);
        properties.setMaxModelCalls(3);
        properties.setTokenBudgetPerRequest(tokens);
        properties.setRequestTimeout(timeout);
        return new AiExecutionGuard(properties, metrics).newBudget("chat");
    }

    private ToolExecutionRequest request() {
        return ToolExecutionRequest.builder().id("call-1").name("query_shop").arguments("{}").build();
    }
}
