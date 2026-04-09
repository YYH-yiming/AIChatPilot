package com.yyh.user.filter;

import com.yyh.common.constant.CommonConstant;
import com.yyh.common.utils.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String authHeader = request.getHeader(CommonConstant.AUTH_HEADER);
//        从http 请求中获取 head里面的token
        if (authHeader != null && authHeader.startsWith(CommonConstant.TOKEN_PREFIX)) {
            String token = authHeader.substring(CommonConstant.TOKEN_PREFIX.length());
            try {
                Claims claims = JwtUtil.parseToken(token, jwtSecret);
                Long userId = JwtUtil.getUserId(claims);
                Long tenantId = JwtUtil.getTenantId(claims);

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(userId, null, Collections.emptyList());

                Map<String, Object> details = new HashMap<>();
                details.put("tenantId", tenantId);
                authentication.setDetails(details);

//                后续controller可以通过Security Context获取当前登录的对象
                SecurityContextHolder.getContext().setAuthentication(authentication);
                log.debug("JWT认证成功: uri={}, userId={}, tenantId={}", request.getRequestURI(), userId, tenantId);
            } catch (Exception ignored) {
                log.warn("JWT解析失败: method={}, uri={}, message={}", request.getMethod(), request.getRequestURI(), ignored.getMessage());
            }
        }

        chain.doFilter(request, response);
    }
}
