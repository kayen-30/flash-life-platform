package com.hmdp.ai.execution;

/** 模型响应不符合约定；与调用方参数错误区分，必须计入模型熔断失败。 */
public final class AiModelProtocolException extends IllegalArgumentException {
    public enum Rejection { UNCLASSIFIED, JSON_SYNTAX, ROOT_STRUCTURE, UNKNOWN_FIELD, FIELD_TYPE, FIELD_LIMIT,
        ENUM_VALUE, QUOTE_NOT_IN_INPUT, QUOTE_LIMIT, SUBTASK_SCOPE }
    public enum Field {
        NONE, ROOT, CAPABILITIES, SHOP_NAME, CONTINUATION, DAY_QUOTE, BUDGET_QUOTE, USAGE_QUOTES,
        SELECTION_GOAL, FAQ_QUESTIONS, UNKNOWN_POLICY_QUOTE;

        /** 只暴露服务端已定义字段；未知属性名可能携带私有内容，不能直接进入日志。 */
        public static Field fromJsonName(String name) {
            if (name == null) return NONE;
            return switch (name) {
                case "capabilities" -> CAPABILITIES;
                case "shopName" -> SHOP_NAME;
                case "continuation" -> CONTINUATION;
                case "dayQuote" -> DAY_QUOTE;
                case "budgetQuote" -> BUDGET_QUOTE;
                case "usageQuotes" -> USAGE_QUOTES;
                case "selectionGoal" -> SELECTION_GOAL;
                case "faqQuestions" -> FAQ_QUESTIONS;
                case "unknownPolicyQuote" -> UNKNOWN_POLICY_QUOTE;
                default -> NONE;
            };
        }
    }
    private final Rejection rejection;
    private final Field field;

    public AiModelProtocolException(String message) { this(message, null); }
    public AiModelProtocolException(String message, Throwable cause) {
        this(message, Rejection.UNCLASSIFIED, Field.NONE, cause);
    }
    public AiModelProtocolException(String message, Rejection rejection, Field field, Throwable cause) {
        super(message, cause);
        this.rejection = rejection;
        this.field = field;
    }
    public Rejection rejection() { return rejection; }
    public Field field() { return field; }
}
