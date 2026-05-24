package com.yyh.analytics;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class AnalyticsAggregationService {

    private final AnalyticsDailyStatsMapper analyticsDailyStatsMapper;
    private final AnalyticsIntentDailyStatsMapper analyticsIntentDailyStatsMapper;
    private final AnalyticsCacheService analyticsCacheService;

    @Transactional(rollbackFor = Exception.class)
    public void consumeChatSession(ChatSessionAnalyticsEvent event) {
        if (event == null || event.getTenantId() == null || event.getEventTime() == null) {
            return;
        }
        AnalyticsDailyStats delta = emptyDailyStats(event.getTenantId(), event.getEventTime().toLocalDate());
        if ("created".equalsIgnoreCase(event.getEventType())) {
            delta.setSessionsCreated(1L);
        } else if ("closed".equalsIgnoreCase(event.getEventType())) {
            delta.setSessionsClosed(1L);
        } else {
            return;
        }
        analyticsDailyStatsMapper.accumulate(delta);
        analyticsCacheService.evictTenant(event.getTenantId());
    }

    @Transactional(rollbackFor = Exception.class)
    public void consumeChatMessage(ChatMessageAnalyticsEvent event) {
        if (event == null || event.getTenantId() == null || event.getEventTime() == null || !StringUtils.hasText(event.getRole())) {
            return;
        }

        AnalyticsDailyStats delta = emptyDailyStats(event.getTenantId(), event.getEventTime().toLocalDate());
        delta.setMessagesTotal(1L);
        if ("user".equalsIgnoreCase(event.getRole())) {
            delta.setUserMessages(1L);
        } else if ("assistant".equalsIgnoreCase(event.getRole())) {
            delta.setAssistantMessages(1L);
            if ("knowledge".equalsIgnoreCase(event.getAnswerSource())) {
                delta.setKnowledgeAnswers(1L);
            }
            if ("agent".equalsIgnoreCase(event.getAnswerSource())) {
                delta.setAgentAnswers(1L);
            }
            if (Boolean.TRUE.equals(event.getGrounded())) {
                delta.setGroundedAnswers(1L);
            }
            if (event.getReferenceCount() != null && event.getReferenceCount() > 0) {
                delta.setReferencesTotal(event.getReferenceCount().longValue());
            }
            if (event.getTokenUsed() != null && event.getTokenUsed() > 0) {
                delta.setTotalTokens(event.getTokenUsed().longValue());
            }
            if (event.getDurationMs() != null && event.getDurationMs() > 0) {
                delta.setTotalDurationMs(event.getDurationMs());
            }
            if ("escalation".equalsIgnoreCase(event.getIntent())) {
                delta.setEscalationCount(1L);
            }
        }
        analyticsDailyStatsMapper.accumulate(delta);

        if ("assistant".equalsIgnoreCase(event.getRole()) && StringUtils.hasText(event.getIntent())) {
            AnalyticsIntentDailyStats intentStats = new AnalyticsIntentDailyStats();
            intentStats.setTenantId(event.getTenantId());
            intentStats.setStatDate(event.getEventTime().toLocalDate());
            intentStats.setIntent(event.getIntent());
            intentStats.setHitCount(1L);
            analyticsIntentDailyStatsMapper.accumulate(intentStats);
        }

        analyticsCacheService.evictTenant(event.getTenantId());
    }

    @Transactional(rollbackFor = Exception.class)
    public void consumeAgentTrace(AgentTraceAnalyticsEvent event) {
        if (event == null || event.getTenantId() == null || event.getEventTime() == null) {
            return;
        }
        if ("router".equalsIgnoreCase(event.getAgentName())) {
            return;
        }

        AnalyticsDailyStats delta = emptyDailyStats(event.getTenantId(), event.getEventTime().toLocalDate());
        delta.setAgentCalls(1L);
        if ("success".equalsIgnoreCase(event.getStatus())) {
            delta.setAgentSuccessCalls(1L);
        } else if ("failed".equalsIgnoreCase(event.getStatus())) {
            delta.setAgentFailedCalls(1L);
        }
        analyticsDailyStatsMapper.accumulate(delta);
        analyticsCacheService.evictTenant(event.getTenantId());
    }

    private AnalyticsDailyStats emptyDailyStats(Long tenantId, LocalDate statDate) {
        AnalyticsDailyStats stats = new AnalyticsDailyStats();
        stats.setTenantId(tenantId);
        stats.setStatDate(statDate);
        stats.setSessionsCreated(0L);
        stats.setSessionsClosed(0L);
        stats.setMessagesTotal(0L);
        stats.setUserMessages(0L);
        stats.setAssistantMessages(0L);
        stats.setKnowledgeAnswers(0L);
        stats.setAgentAnswers(0L);
        stats.setGroundedAnswers(0L);
        stats.setReferencesTotal(0L);
        stats.setTotalTokens(0L);
        stats.setTotalDurationMs(0L);
        stats.setAgentCalls(0L);
        stats.setAgentSuccessCalls(0L);
        stats.setAgentFailedCalls(0L);
        stats.setEscalationCount(0L);
        return stats;
    }
}
