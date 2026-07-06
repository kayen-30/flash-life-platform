package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.AiChatRequest;
import com.hmdp.dto.Result;
import com.hmdp.service.IAiCustomerService;
import com.hmdp.service.ai.AiCustomerKnowledgeService;
import com.hmdp.service.ai.AiCustomerTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class AiCustomerServiceImpl implements IAiCustomerService {

    private final ChatClient chatClient;
    private final AiCustomerKnowledgeService knowledgeService;
    private final AiCustomerTools customerTools;

    /**
     * 构建智能客服客户端；未启用模型时允许为空，保证普通业务可独立启动。
     */
    public AiCustomerServiceImpl(ObjectProvider<ChatClient.Builder> chatClientBuilderProvider,
                                 AiCustomerKnowledgeService knowledgeService,
                                 AiCustomerTools customerTools) {
        ChatClient.Builder builder = chatClientBuilderProvider.getIfAvailable();
        this.chatClient = builder == null ? null : builder
                .defaultSystem("你是黑马点评的智能客服，回答要简洁、准确、友好，只围绕平台业务回答。不要使用 Markdown、emoji、标题、项目符号或星号加粗。")
                .build();
        this.knowledgeService = knowledgeService;
        this.customerTools = customerTools;
    }

    /**
     * RAG + Function Calling 客服入口，先召回业务知识，再让模型按需调用业务工具。
     */
    @Override
    public Result chat(AiChatRequest request) {
        if (request == null || StrUtil.isBlank(request.getMessage())) {
            return Result.fail("请输入要咨询的问题");
        }
        if (chatClient == null) {
            return Result.fail("AI 客服未启用，请在后端启动配置中设置 DEEPSEEK_API_KEY，并开启 Spring AI Chat。");
        }

        String knowledgeContext = knowledgeService.retrieve(request.getMessage(), request.getShopId());
        String userPrompt = buildUserPrompt(request, knowledgeContext);
        try {
            String answer = chatClient.prompt()
                    .tools(customerTools)
                    .user(userPrompt)
                    .call()
                    .content();
            return Result.ok(answer);
        } catch (RuntimeException e) {
            // 模型或工具调用属于外部链路，失败时返回稳定错误并保留日志便于排查。
            log.warn("智能客服回答失败，message={}", request.getMessage(), e);
            return Result.fail("智能客服暂时无法回答，请稍后再试");
        }
    }

    private String buildUserPrompt(AiChatRequest request, String knowledgeContext) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请根据以下业务知识和可用工具回答用户问题。\n")
                .append("要求：如果问题涉及店铺、优惠券、热门笔记等实时数据，优先调用工具查询；")
                .append("如果知识库没有相关依据，请说明暂时无法确认，不要编造；")
                .append("输出为普通中文自然段，不要 Markdown，不要 emoji，不要使用 **、###、列表符号。\n\n")
                .append("【召回知识】\n")
                .append(knowledgeContext)
                .append("\n【用户问题】\n")
                .append(request.getMessage());
        if (request.getShopId() != null) {
            prompt.append("\n【当前店铺ID】").append(request.getShopId());
        }
        return prompt.toString();
    }
}
