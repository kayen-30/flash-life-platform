package com.hmdp.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 统一校验服务间共享凭证，避免网关用户上下文与内部接口的信任条件不一致。
 */
public final class InternalTokenValidator {

    private InternalTokenValidator() {
    }

    /**
     * 空凭证一律不可信，并以常量时间比较有效凭证。
     */
    public static boolean matches(String expectedToken, String actualToken) {
        if (expectedToken == null || expectedToken.isBlank() || actualToken == null) {
            return false;
        }
        // 使用常量时间比较，避免凭证校验泄露前缀匹配信息。
        return MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8),
                actualToken.getBytes(StandardCharsets.UTF_8)
        );
    }
}
