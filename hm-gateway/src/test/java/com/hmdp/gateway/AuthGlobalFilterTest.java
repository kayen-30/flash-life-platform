package com.hmdp.gateway;

import com.hmdp.utils.GatewayHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveHashOperations;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthGlobalFilterTest {

    @Test
    void validTokenShouldPassOnceWithoutWritingUnauthorizedResponse() {
        ReactiveStringRedisTemplate redisTemplate = mock(ReactiveStringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ReactiveHashOperations<String, Object, Object> hashOperations = mock(ReactiveHashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get("login:token:valid-token", "id")).thenReturn(Mono.just("42"));
        when(redisTemplate.expire(eq("login:token:valid-token"), any(Duration.class)))
                .thenReturn(Mono.just(true));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/voucher-order/seckill/16")
                        .header("authorization", "valid-token")
                        .build()
        );
        AtomicInteger invocationCount = new AtomicInteger();
        GatewayFilterChain chain = authenticatedExchange -> {
            invocationCount.incrementAndGet();
            assertThat(authenticatedExchange.getRequest().getHeaders().getFirst(GatewayHeaders.USER_ID))
                    .isEqualTo("42");
            assertThat(authenticatedExchange.getRequest().getHeaders().getFirst(GatewayHeaders.USER_ROLE))
                    .isEqualTo("ADMIN");
            assertThat(authenticatedExchange.getRequest().getHeaders().getFirst(GatewayHeaders.INTERNAL_TOKEN))
                    .isEqualTo("trusted-internal-token");
            return Mono.empty();
        };

        // 下游 Mono<Void> 正常完成后，网关不能再次进入未登录分支。
        new AuthGlobalFilter(redisTemplate, "1,42", "trusted-internal-token")
                .filter(exchange, chain)
                .block(Duration.ofSeconds(1));

        assertThat(invocationCount).hasValue(1);
        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().isCommitted()).isFalse();
        verify(redisTemplate).expire(eq("login:token:valid-token"), any(Duration.class));
    }

    @Test
    void publicRequestShouldStripSpoofedTrustHeaders() {
        ReactiveStringRedisTemplate redisTemplate = mock(ReactiveStringRedisTemplate.class);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/shop/1")
                        .header(GatewayHeaders.USER_ID, "999")
                        .header(GatewayHeaders.USER_ROLE, "ADMIN")
                        .header(GatewayHeaders.INTERNAL_TOKEN, "forged-token")
                        .build()
        );
        GatewayFilterChain chain = forwardedExchange -> {
            assertThat(forwardedExchange.getRequest().getHeaders().containsKey(GatewayHeaders.USER_ID)).isFalse();
            assertThat(forwardedExchange.getRequest().getHeaders().containsKey(GatewayHeaders.USER_ROLE)).isFalse();
            assertThat(forwardedExchange.getRequest().getHeaders().containsKey(GatewayHeaders.INTERNAL_TOKEN)).isFalse();
            return Mono.empty();
        };

        new AuthGlobalFilter(redisTemplate, "1", "trusted-internal-token")
                .filter(exchange, chain)
                .block(Duration.ofSeconds(1));
    }
}
