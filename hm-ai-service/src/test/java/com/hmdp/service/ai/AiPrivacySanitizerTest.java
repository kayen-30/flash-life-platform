package com.hmdp.service.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiPrivacySanitizerTest {

    private final AiPrivacySanitizer sanitizer = new AiPrivacySanitizer();

    @Test
    void shouldMaskCommonPersonalIdentifiersBeforeCallingModel() {
        String result = sanitizer.sanitizeForModel("请联系 13800138000，邮箱 test@example.com，证件 11010519491231002X");

        assertEquals("请联系 [手机号已脱敏]，邮箱 [邮箱已脱敏]，证件 [证件号已脱敏]", result);
    }

    @Test
    void shouldHandleLongInvalidEmailInput() {
        String value = "%".repeat(10_000);

        assertEquals(value, sanitizer.sanitizeForModel(value));
    }

    @Test
    void shouldLimitByCodePointWithoutSplittingEmoji() {
        assertEquals("你好", sanitizer.limit("你好😀世界", 2));
        assertEquals("你好😀", sanitizer.limit("你好😀世界", 3));
    }
}
