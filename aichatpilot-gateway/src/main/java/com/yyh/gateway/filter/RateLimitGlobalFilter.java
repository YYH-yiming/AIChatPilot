package com.yyh.gateway.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyh.common.result.Result;
import com.yyh.common.result.ResultCode;
import com.yyh.gateway.config.GatewayRateLimitProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitGlobalFilter implements GlobalFilter, Ordered {

    private final GatewayRateLimitProperties properties;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!properties.isEnabled() || isPreflight(exchange) || isWhiteListed(exchange.getRequest().getURI().getPath())) {
            return chain.filter(exchange);
        }

        String routeId = resolveRouteId(exchange);
        int limit = properties.effectiveLimit(routeId);
        if (limit <= 0) {
            return chain.filter(exchange);
        }

        String clientKey = resolveClientKey(exchange);
        String redisKey = buildRedisKey(routeId, clientKey);

        return checkRateLimit(routeId, clientKey, redisKey, limit)
                .flatMap(allowed -> allowed
                        ? chain.filter(exchange)
                        : writeTooManyRequests(exchange.getResponse(), limit, properties.getWindowSeconds()));
    }

    private Mono<Boolean> checkRateLimit(String routeId, String clientKey, String redisKey, int limit) {
        return redisTemplate.opsForValue().increment(redisKey)
                .flatMap(current -> {
                    Mono<Boolean> expireOp = current != null && current == 1L
                            ? redisTemplate.expire(redisKey, properties.windowTtl())
                            : Mono.just(Boolean.TRUE);
                    return expireOp.thenReturn(current == null ? 0L : current);
                })
                .map(current -> {
                    if (current > limit) {
                        log.warn("Gateway限流触发: routeId={}, clientKey={}, current={}, limit={}",
                                routeId, clientKey, current, limit);
                        return false;
                    }
                    return true;
                })
                .onErrorResume(ex -> {
                    if (properties.isFailOpen()) {
                        log.warn("Gateway限流检查失败，按fail-open放行: routeId={}, clientKey={}, message={}",
                                routeId, clientKey, ex.getMessage());
                        return Mono.just(true);
                    }
                    return Mono.just(false);
                });
    }

    @Override
    public int getOrder() {
        return -90;
    }

    private boolean isPreflight(ServerWebExchange exchange) {
        return exchange.getRequest().getMethod() != null
                && "OPTIONS".equalsIgnoreCase(exchange.getRequest().getMethod().name());
    }

    private boolean isWhiteListed(String path) {
        return properties.getWhiteList().stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    private String resolveRouteId(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route != null && StringUtils.hasText(route.getId())) {
            return route.getId();
        }
        return exchange.getRequest().getPath().value();
    }

    private String resolveClientKey(ServerWebExchange exchange) {
        String tenantId = exchange.getRequest().getHeaders().getFirst("X-Tenant-Id");
        if (StringUtils.hasText(tenantId)) {
            return "tenant:" + tenantId;
        }
        String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
        if (StringUtils.hasText(userId)) {
            return "user:" + userId;
        }
        if (exchange.getRequest().getRemoteAddress() != null
                && exchange.getRequest().getRemoteAddress().getAddress() != null) {
            return "ip:" + exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
        }
        return "anonymous";
    }

    private String buildRedisKey(String routeId, String clientKey) {
        long window = Instant.now().getEpochSecond() / Math.max(properties.getWindowSeconds(), 1);
        return "gateway:rate-limit:" + routeId + ":" + clientKey + ":" + window;
    }

    private Mono<Void> writeTooManyRequests(ServerHttpResponse response, int limit, int windowSeconds) {
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        Result<Void> body = Result.error(ResultCode.TOO_MANY_REQUESTS,
                "请求过于频繁，请在" + windowSeconds + "秒后重试（当前窗口限制" + limit + "次）");
        byte[] bytes = toJson(body).getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    private String toJson(Result<Void> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            return "{\"code\":429,\"message\":\"请求过于频繁\",\"data\":null}";
        }
    }
}
