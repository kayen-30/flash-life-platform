package com.hmdp.ai.execution;

/**
 * AI 调用被本地保护机制拒绝的固定原因，便于调用方返回稳定提示并聚合指标。
 */
public enum AiExecutionRejectionReason {

    CONCURRENCY_LIMIT,

    CIRCUIT_OPEN
}
