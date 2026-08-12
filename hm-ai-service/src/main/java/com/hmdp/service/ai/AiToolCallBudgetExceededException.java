package com.hmdp.service.ai;

/**
 * 模型发起的工具调用或后续模型回合超过单次请求预算时中断编排，避免继续放大外部依赖与成本。
 */
public final class AiToolCallBudgetExceededException extends IllegalArgumentException {

    public AiToolCallBudgetExceededException() {
        super("AI 工具调用或后续模型回合超过本轮预算");
    }
}
