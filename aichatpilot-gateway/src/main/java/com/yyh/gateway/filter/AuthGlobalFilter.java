package com.yyh.gateway.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyh.common.constant.CommonConstant;
import com.yyh.common.result.Result;
import com.yyh.common.result.ResultCode;
import com.yyh.common.utils.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private static final List<String> WHITE_LIST = List.of(
            "/api/user/login",
            "/api/user/register",
            "/api/user/**/login",
            "/api/user/**/register",
            "/actuator/**",
            "/favicon.ico"
    );

    private final ObjectMapper objectMapper;

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (isWhiteListed(path) || isPreflight(exchange)) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst(CommonConstant.AUTH_HEADER);
        if (authHeader == null || !authHeader.startsWith(CommonConstant.TOKEN_PREFIX)) {
            return writeUnauthorized(exchange.getResponse(), "缺少或无效的Authorization请求头");
        }

        String token = authHeader.substring(CommonConstant.TOKEN_PREFIX.length());
        try {
            Claims claims = JwtUtil.parseToken(token, jwtSecret);
            Long userId = JwtUtil.getUserId(claims);
            Long tenantId = JwtUtil.getTenantId(claims);
            if (userId == null) {
                return writeUnauthorized(exchange.getResponse(), "Token中缺少用户信息");
            }

            ServerHttpRequest mutatedRequest = exchange.getRequest()
                    .mutate()
                    .header("X-User-Id", String.valueOf(userId))
                    .header("X-Tenant-Id", tenantId == null ? "" : String.valueOf(tenantId))
                    .build();

            log.debug("Gateway鉴权成功: path={}, userId={}, tenantId={}", path, userId, tenantId);
            return chain.filter(exchange.mutate().request(mutatedRequest).build());
        } catch (Exception ex) {
            log.warn("Gateway JWT校验失败: path={}, message={}", path, ex.getMessage());
            return writeUnauthorized(exchange.getResponse(), "登录已过期或Token无效");
        }
    }

    @Override
    public int getOrder() {
        return -100;
    }

    private boolean isWhiteListed(String path) {
        return WHITE_LIST.stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    private boolean isPreflight(ServerWebExchange exchange) {
        return "OPTIONS".equalsIgnoreCase(exchange.getRequest().getMethod().name());
    }

    private Mono<Void> writeUnauthorized(ServerHttpResponse response, String message) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        Result<Void> body = Result.error(ResultCode.UNAUTHORIZED, message);
        byte[] bytes = toJson(body).getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    private String toJson(Result<Void> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            return "{\"code\":401,\"message\":\"未认证\",\"data\":null}";
        }
    }
}
