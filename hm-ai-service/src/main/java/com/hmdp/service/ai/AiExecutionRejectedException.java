package com.hmdp.service.ai;

/**
 * AI 调用尚未发往模型服务时被本地保护机制拒绝。
 */
public final class AiExecutionRejectedException extends RuntimeException {

    private final AiExecutionRejectionReason reason;

    public AiExecutionRejectedException(AiExecutionRejectionReason reason) {
        super("AI execution rejected: " + reason);
        this.reason = reason;
    }

    public AiExecutionRejectionReason getReason() {
        return reason;
    }
}
