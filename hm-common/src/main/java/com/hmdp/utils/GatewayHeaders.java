package com.hmdp.utils;

/**
 * 网关和内部服务约定的可信请求头，外部请求中的同名请求头必须由网关先移除。
 */
public final class GatewayHeaders {

    public static final String USER_ID = "X-User-Id";
    public static final String USER_ROLE = "X-User-Role";
    public static final String INTERNAL_TOKEN = "X-Internal-Token";

    private GatewayHeaders() {
    }
}
