package com.yyh.analytics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyh.analytics.config.AnalyticsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyticsCacheService {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final AnalyticsProperties analyticsProperties;

    public AnalyticsDashboardVO getDashboard(Long tenantId, int days) {
        String cached = stringRedisTemplate.opsForValue().get(buildDashboardKey(tenantId, days));
        if (cached == null || cached.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(cached, AnalyticsDashboardVO.class);
        } catch (Exception ex) {
            log.warn("解析Analytics缓存失败: tenantId={}, days={}, message={}", tenantId, days, ex.getMessage());
            evictDashboard(tenantId, days);
            return null;
        }
    }

    public void putDashboard(Long tenantId, int days, AnalyticsDashboardVO dashboard) {
        try {
            stringRedisTemplate.opsForValue().set(
                    buildDashboardKey(tenantId, days),
                    objectMapper.writeValueAsString(dashboard),
                    Duration.ofMinutes(analyticsProperties.getCache().getTtlMinutes())
            );
        } catch (JsonProcessingException ex) {
            log.warn("写入Analytics缓存失败: tenantId={}, days={}, message={}", tenantId, days, ex.getMessage());
        }
    }

    public void evictTenant(Long tenantId) {
        int maxDays = Math.max(analyticsProperties.getQuery().getMaxDays(), analyticsProperties.getQuery().getDefaultDays());
        for (int days = 1; days <= maxDays; days++) {
            evictDashboard(tenantId, days);
        }
    }

    private void evictDashboard(Long tenantId, int days) {
        stringRedisTemplate.delete(buildDashboardKey(tenantId, days));
    }

    private String buildDashboardKey(Long tenantId, int days) {
        return "analytics:dashboard:tenant:" + tenantId + ":days:" + days;
    }
}
