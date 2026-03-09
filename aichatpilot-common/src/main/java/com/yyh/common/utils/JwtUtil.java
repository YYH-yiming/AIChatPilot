package com.yyh.common.utils;

import com.yyh.common.constant.CommonConstant;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

public final class JwtUtil {

    private JwtUtil() {}

    private static final String DEFAULT_SECRET = "AIChatPilot-SecretKey-Must-Be-At-Least-32-Bytes!";

    private static SecretKey getSigningKey(String secret) {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public static String generateToken(Long userId, Long tenantId, String secret) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + CommonConstant.TOKEN_EXPIRE_MS);

        return Jwts.builder()
                .claim(CommonConstant.CLAIM_USER_ID, userId)
                .claim(CommonConstant.CLAIM_TENANT_ID, tenantId)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(getSigningKey(secret))
                .compact();
    }

    public static String generateToken(Long userId, Long tenantId) {
        return generateToken(userId, tenantId, DEFAULT_SECRET);
    }

    public static Claims parseToken(String token, String secret) {
        return Jwts.parser()
                .verifyWith(getSigningKey(secret))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public static Claims parseToken(String token) {
        return parseToken(token, DEFAULT_SECRET);
    }

    public static Long getUserId(Claims claims) {
        return claims.get(CommonConstant.CLAIM_USER_ID, Long.class);
    }

    public static Long getTenantId(Claims claims) {
        return claims.get(CommonConstant.CLAIM_TENANT_ID, Long.class);
    }
}
