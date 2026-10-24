package com.hmdp.ai.execution;

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

    // 逐模型调用会重复计入历史输入，不能沿用旧的工具预留口径。
    // 按需激活会重复计入 Skill 和工具上下文，为四轮调用预留有界空间。
    private int tokenBudgetPerRequest = 12288;
    // 官方 Skill 激活占一个模型工具回合，为顺序查店和查券留足四轮。
    private int maxModelCalls = 4;
    private Duration requestTimeout = Duration.ofSeconds(25);

    private Duration httpConnectTimeout = Duration.ofSeconds(2);

    private Duration httpReadTimeout = Duration.ofSeconds(15);

    public int getMaxModelCalls() { return maxModelCalls; }
    public void setMaxModelCalls(int value) { this.maxModelCalls = value; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration value) { this.requestTimeout = value; }

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
