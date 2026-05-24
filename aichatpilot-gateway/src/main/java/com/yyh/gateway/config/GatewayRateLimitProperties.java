package com.yyh.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "gateway.rate-limit")
public class GatewayRateLimitProperties {

    private boolean enabled = true;
    private int windowSeconds = 1;
    private int requestsPerWindow = 40;
    private int burstExtraRequests = 20;
    private boolean failOpen = true;
    private List<String> whiteList = new ArrayList<>(List.of("/actuator/**", "/favicon.ico"));
    private Map<String, Integer> routeLimits = new LinkedHashMap<>();

    public int resolveLimit(String routeId) {
        return routeLimits.getOrDefault(routeId, requestsPerWindow);
    }

    public int effectiveLimit(String routeId) {
        return Math.max(0, resolveLimit(routeId) + Math.max(0, burstExtraRequests));
    }

    public Duration windowTtl() {
        return Duration.ofSeconds(Math.max(windowSeconds, 1) + 1L);
    }
}
