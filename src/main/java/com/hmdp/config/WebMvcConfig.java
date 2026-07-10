package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.annotation.Resource;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    static final String[] PUBLIC_ENDPOINTS = {
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/user/code",
            "/user/login",
            "/user/info/**",
            "/shop/*",
            "/shop/*/ai-recommend",
            "/shop/of/type",
            "/shop/of/name",
            "/shop-type/list",
            "/voucher/list/*",
            "/blog/hot"
    };

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Bean
    public LoginInterceptor loginInterceptor() {
        return new LoginInterceptor();
    }

    @Bean
    public RefreshTokenInterceptor refreshTokenInterceptor() {
        return new RefreshTokenInterceptor(stringRedisTemplate);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(refreshTokenInterceptor())
                .addPathPatterns("/**")
                .order(0);
        registry.addInterceptor(loginInterceptor())
                // 只放行查询和登录相关接口，新增、修改、上传等写操作必须登录。
                .excludePathPatterns(PUBLIC_ENDPOINTS)
                .order(1);
    }
}
