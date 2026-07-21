package com.hmdp.utils;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 内部接口只接受携带共享密钥的服务调用，避免业务端口暴露后被直接访问。
 */
public class InternalApiInterceptor implements HandlerInterceptor {

    private final byte[] expectedToken;

    public InternalApiInterceptor(String expectedToken) {
        this.expectedToken = expectedToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String actualToken = request.getHeader(GatewayHeaders.INTERNAL_TOKEN);
        boolean trusted = actualToken != null && MessageDigest.isEqual(
                expectedToken,
                actualToken.getBytes(StandardCharsets.UTF_8)
        );
        if (!trusted) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        }
        return trusted;
    }
}
