package com.hmdp.api;

import com.hmdp.utils.GatewayHeaders;
import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

/**
 * Feign 调用统一携带内部凭证，用户身份仍由业务入口显式传递。
 */
public class InternalFeignConfiguration {

    @Bean
    public RequestInterceptor internalTokenInterceptor(
            @Value("${hmdp.internal-token}") String internalToken) {
        return template -> template.header(GatewayHeaders.INTERNAL_TOKEN, internalToken);
    }
}
