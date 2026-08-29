package com.hmdp.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 为 AI 外部调用保留统一的 RestClient 超时配置；LangChain4j 模型自身的超时由模型 Bean 配置。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiExecutionProperties.class)
public class AiReliabilityConfiguration {

    /**
     * Boot 管理的 RestClient Builder 使用 AI 外部调用的统一超时阈值。
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
