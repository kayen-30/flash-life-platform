package com.hmdp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * AI 外部调用的本地保护阈值；变更后需要重启服务，避免运行中改变并发语义。
 */
@ConfigurationProperties(prefix = "hmdp.ai.execution")
public class AiExecutionProperties {

    private int maxConcurrentRequests = 8;

    private Duration acquireTimeout = Duration.ofMillis(100);

    private int circuitFailureThreshold = 5;

    private Duration circuitOpenDuration = Duration.ofSeconds(30);

    private int maxToolCalls = 4;

    private int tokenBudgetPerRequest = 2048;

    private Duration httpConnectTimeout = Duration.ofSeconds(2);

    private Duration httpReadTimeout = Duration.ofSeconds(15);

    public int getMaxConcurrentRequests() {
        return maxConcurrentRequests;
    }

    public void setMaxConcurrentRequests(int maxConcurrentRequests) {
        this.maxConcurrentRequests = maxConcurrentRequests;
    }

    public Duration getAcquireTimeout() {
        return acquireTimeout;
    }

    public void setAcquireTimeout(Duration acquireTimeout) {
        this.acquireTimeout = acquireTimeout;
    }

    public int getCircuitFailureThreshold() {
        return circuitFailureThreshold;
    }

    public void setCircuitFailureThreshold(int circuitFailureThreshold) {
        this.circuitFailureThreshold = circuitFailureThreshold;
    }

    public Duration getCircuitOpenDuration() {
        return circuitOpenDuration;
    }

    public void setCircuitOpenDuration(Duration circuitOpenDuration) {
        this.circuitOpenDuration = circuitOpenDuration;
    }

    public int getMaxToolCalls() {
        return maxToolCalls;
    }

    public void setMaxToolCalls(int maxToolCalls) {
        this.maxToolCalls = maxToolCalls;
    }

    public int getTokenBudgetPerRequest() {
        return tokenBudgetPerRequest;
    }

    public void setTokenBudgetPerRequest(int tokenBudgetPerRequest) {
        this.tokenBudgetPerRequest = tokenBudgetPerRequest;
    }

    public Duration getHttpConnectTimeout() {
        return httpConnectTimeout;
    }

    public void setHttpConnectTimeout(Duration httpConnectTimeout) {
        this.httpConnectTimeout = httpConnectTimeout;
    }

    public Duration getHttpReadTimeout() {
        return httpReadTimeout;
    }

    public void setHttpReadTimeout(Duration httpReadTimeout) {
        this.httpReadTimeout = httpReadTimeout;
    }
}
