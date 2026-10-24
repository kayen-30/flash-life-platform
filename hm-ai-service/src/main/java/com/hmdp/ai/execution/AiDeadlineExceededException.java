package com.hmdp.ai.execution;

/** 截止或剩余时长不足不表示模型服务故障，不触发熔断。 */
public class AiDeadlineExceededException extends IllegalArgumentException {
    public AiDeadlineExceededException() { super("本轮咨询已达到总时限，请稍后重试"); }
}
