package com.hmdp.gateway;

import com.hmdp.auth.UserRoles;
import com.hmdp.utils.GatewayHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 网关统一校验登录态，并向下游传递可信用户 ID。
 */
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private static final String AUTHORIZATION = "authorization";
    private static final String LOGIN_USER_KEY = "login:token:";
    private static final Duration LOGIN_TTL = Duration.ofMinutes(36000);
    private static final List<String> PUBLIC_PATHS = List.of(
            "/user/code",
            "/user/login",
            "/user/info/**",
            "/shop/*",
            "/shop/of/type",
            "/shop/of/name",
            "/shop-type/list",
            "/voucher/list/*",
            "/blog/hot",
            "/v3/api-docs/**",
            "/swagger-ui/**"
    );

    private final ReactiveStringRedisTemplate redisTemplate;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final Set<Long> adminUserIds;

    public AuthGlobalFilter(
            ReactiveStringRedisTemplate redisTemplate,
            @Value("${hmdp.admin-user-ids:1}") String adminUserIds) {
        this.redisTemplate = redisTemplate;
        this.adminUserIds = parseAdminUserIds(adminUserIds);
    }

    /**
     * 清理外部身份头，校验 Token 后再把可信用户 ID 传给下游。
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 移除外部同名请求头，防止客户端绕过网关伪造用户身份。
        ServerHttpRequest cleanedRequest = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(GatewayHeaders.USER_ID);
                    headers.remove(GatewayHeaders.USER_ROLE);
                    headers.remove(GatewayHeaders.INTERNAL_TOKEN);
                })
                .build();
        ServerWebExchange cleanedExchange = exchange.mutate().request(cleanedRequest).build();
        String path = cleanedRequest.getURI().getPath();
        String token = cleanedRequest.getHeaders().getFirst(AUTHORIZATION);

        if (token == null || token.isBlank()) {
            return isPublic(path) ? chain.filter(cleanedExchange) : unauthorized(cleanedExchange);
        }

        String tokenKey = LOGIN_USER_KEY + token;
        return redisTemplate.opsForHash().get(tokenKey, "id")
                .map(Object::toString)
                .defaultIfEmpty("")
                .flatMap(userId -> {
                    if (userId.isBlank()) {
                        return isPublic(path)
                                ? chain.filter(cleanedExchange)
                                : unauthorized(cleanedExchange);
                    }

                    ServerHttpRequest authenticated = cleanedRequest.mutate()
                            .header(GatewayHeaders.USER_ID, userId)
                            .header(GatewayHeaders.USER_ROLE, resolveRole(userId))
                            .build();
                    ServerWebExchange authenticatedExchange = cleanedExchange.mutate()
                            .request(authenticated)
                            .build();
                    // 显式处理认证结果，避免把下游 Mono<Void> 的正常空完成误判为未登录。
                    return redisTemplate.expire(tokenKey, LOGIN_TTL)
                            .then(chain.filter(authenticatedExchange));
                });
    }

    private boolean isPublic(String path) {
        return PUBLIC_PATHS.stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    private String resolveRole(String userId) {
        try {
            return adminUserIds.contains(Long.valueOf(userId)) ? UserRoles.ADMIN : UserRoles.USER;
        } catch (NumberFormatException e) {
            return UserRoles.USER;
        }
    }

    private Set<Long> parseAdminUserIds(String configuredIds) {
        if (configuredIds == null || configuredIds.isBlank()) {
            return Set.of();
        }
        return List.of(configuredIds.split(",")).stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(Long::valueOf)
                .collect(Collectors.toUnmodifiableSet());
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        byte[] body = "{\"success\":false,\"errorMsg\":\"请先登录\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(body))
        );
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
