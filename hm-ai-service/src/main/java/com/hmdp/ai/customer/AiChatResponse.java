package com.hmdp.ai.customer;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "AI 客服回复")
public class AiChatResponse {
    public AiChatResponse(String conversationId, String answer, List<String> sources) {
        this(conversationId, answer, sources, null);
    }
    public AiChatResponse(String conversationId, String answer, List<String> sources,
                          com.hmdp.ai.agent.CustomerAgent.Selection selection) {
        this(conversationId, answer, sources, selection, null);
    }
    public AiChatResponse(String conversationId, String answer, List<String> sources,
                          com.hmdp.ai.agent.CustomerAgent.Selection selection, CustomerTask.Outcome task) {
        this(conversationId, answer, sources, selection, task, List.of());
    }
    public AiChatResponse(String conversationId, String answer, List<String> sources,
                          com.hmdp.ai.agent.CustomerAgent.Selection selection, CustomerTask.Outcome task,
                          List<FaqAnswers.Item> faqAnswers) {
        this(conversationId, answer, sources, selection, task, faqAnswers, List.of(), null);
    }

    @Schema(description = "当前会话标识")
    private String conversationId;

    @Schema(description = "客服回复内容")
    private String answer;

    @Schema(description = "模型选用且属于本轮允许证据的知识来源")
    private List<String> sources;

    @Schema(description = "同店选券建议；ID与原文引用已校验，规则语义仍需确认")
    private com.hmdp.ai.agent.CustomerAgent.Selection selection;

    @Schema(description = "服务端按实际执行汇总的任务状态、原因与本轮调用数")
    private CustomerTask.Outcome task;

    @Schema(description = "逐个平台子问题的状态、实际来源、缺失细节与原因；不以一个来源替整组问题宣称完成")
    private List<FaqAnswers.Item> faqAnswers = List.of();

    @Schema(description = "本轮实际采用且返回前仍有权读取的文档及发布版本")
    private List<EvidenceReference> evidence = List.of();

    @Schema(description = "本轮完整优惠券查询结果的获取时点，不表示库存预留或购买保证")
    private java.time.Instant selectionObservedAt;

    public record EvidenceReference(String documentId, String title, String documentVersion, String publicationVersion) { }
}
