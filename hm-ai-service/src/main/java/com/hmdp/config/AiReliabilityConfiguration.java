package com.hmdp.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 为 Spring AI 同步模型调用补充网络超时配置。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiExecutionProperties.class)
public class AiReliabilityConfiguration {

    /**
     * Spring AI 1.1 未提供 OpenAI HTTP 超时属性，但会注入 Boot 管理的 RestClient.Builder。
     */
    @Bean
    public RestClientCustomizer aiRestClientTimeoutCustomizer(AiExecutionProperties properties) {
        Duration connectTimeout = requirePositive(properties.getHttpConnectTimeout(), "http-connect-timeout");
        Duration readTimeout = requirePositive(properties.getHttpReadTimeout(), "http-read-timeout");
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withTimeouts(connectTimeout, readTimeout);
        return builder -> builder.requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings));
    }

    private Duration requirePositive(Duration value, String propertyName) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("hmdp.ai.execution." + propertyName + " 必须大于 0");
        }
        return value;
    }
}
