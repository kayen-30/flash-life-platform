package com.hmdp.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger 文档配置，统一描述项目接口和登录 token 的请求头规则。
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "黑马点评接口文档",
                version = "1.0.0",
                description = "用于黑马点评学习项目的接口调试，秒杀压测仍建议使用 JMeter。",
                contact = @Contact(name = "hm-dianping")
        )
)
@SecurityScheme(
        name = OpenApiConfig.AUTHORIZATION_HEADER,
        type = SecuritySchemeType.APIKEY,
        in = SecuritySchemeIn.HEADER
)
public class OpenApiConfig {

    public static final String AUTHORIZATION_HEADER = "authorization";
}
