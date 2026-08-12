package com.hmdp.utils;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 内部接口只接受携带共享密钥的服务调用，避免业务端口暴露后被直接访问。
 */
public class InternalApiInterceptor implements HandlerInterceptor {

    private final String expectedToken;

    public InternalApiInterceptor(String expectedToken) {
        this.expectedToken = expectedToken;
    }

    /**
     * 内部接口缺少可信凭证时直接拒绝，不能作为匿名接口继续处理。
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String actualToken = request.getHeader(GatewayHeaders.INTERNAL_TOKEN);
        boolean trusted = InternalTokenValidator.matches(expectedToken, actualToken);
        if (!trusted) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        }
        return trusted;
    }
}
