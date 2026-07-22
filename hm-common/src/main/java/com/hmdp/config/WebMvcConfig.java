package com.hmdp.config;

import com.hmdp.utils.InternalApiInterceptor;
import com.hmdp.utils.AdminAuthorizationInterceptor;
import com.hmdp.utils.UserContextInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 业务服务统一恢复网关用户上下文，并保护不经过网关暴露的内部接口。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${hmdp.internal-token}")
    private String internalToken;

    /**
     * 先验证网关用户上下文，再执行内部接口和管理员权限校验。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new UserContextInterceptor(internalToken))
                .addPathPatterns("/**")
                .order(0);
        registry.addInterceptor(new InternalApiInterceptor(internalToken))
                .addPathPatterns("/internal/**")
                .order(1);
        registry.addInterceptor(new AdminAuthorizationInterceptor())
                .addPathPatterns("/**")
                .order(2);
    }
}
