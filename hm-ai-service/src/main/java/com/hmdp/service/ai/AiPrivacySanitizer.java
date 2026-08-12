package com.hmdp.service.ai;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 进入模型和会话存储前去除常见敏感标识，避免把原始个人信息发送到外部模型或写入 Redis。
 */
@Component
public class AiPrivacySanitizer {

    private static final Pattern MOBILE_PATTERN = Pattern.compile("(?<!\\d)1\\d{10}(?!\\d)");
    private static final Pattern ID_CARD_PATTERN = Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}");
    private static final Pattern CONTROL_PATTERN = Pattern.compile("[\\p{Cntrl}&&[^\\r\\n\\t]]");

    public String sanitizeForModel(String value) {
        if (value == null) {
            return "";
        }
        String sanitized = CONTROL_PATTERN.matcher(value).replaceAll(" ");
        sanitized = MOBILE_PATTERN.matcher(sanitized).replaceAll("[手机号已脱敏]");
        sanitized = ID_CARD_PATTERN.matcher(sanitized).replaceAll("[证件号已脱敏]");
        return EMAIL_PATTERN.matcher(sanitized).replaceAll("[邮箱已脱敏]");
    }

    public String limit(String value, int maxCodePoints) {
        if (value == null || maxCodePoints <= 0) {
            return "";
        }
        int count = value.codePointCount(0, value.length());
        if (count <= maxCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }
}
