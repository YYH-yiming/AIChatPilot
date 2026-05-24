package com.yyh.analytics;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yyh.analytics.config.AnalyticsProperties;
import com.yyh.analytics.support.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AnalyticsQueryService {

    private final AnalyticsProperties analyticsProperties;
    private final AnalyticsCacheService analyticsCacheService;
    private final AnalyticsRawStatsMapper analyticsRawStatsMapper;
    private final AnalyticsDailyStatsMapper analyticsDailyStatsMapper;

    public AnalyticsDashboardVO getDashboard(Integer days) {
        int normalizedDays = normalizeDays(days);
        Long tenantId = SecurityUtils.currentTenantId();

        AnalyticsDashboardVO cached = analyticsCacheService.getDashboard(tenantId, normalizedDays);
        if (cached != null) {
            return cached;
        }

        AnalyticsDashboardVO dashboard = buildDashboard(tenantId, normalizedDays);
        analyticsCacheService.putDashboard(tenantId, normalizedDays, dashboard);
        return dashboard;
    }

    public AnalyticsOverviewVO getOverview(Integer days) {
        return getDashboard(days).getOverview();
    }

    public List<AnalyticsTrendPointVO> getTrends(Integer days) {
        return getDashboard(days).getTrends();
    }

    public List<AnalyticsIntentStatVO> getIntents(Integer days) {
        return getDashboard(days).getIntents();
    }

    public List<AnalyticsSourceStatVO> getSources(Integer days) {
        return getDashboard(days).getSources();
    }

    public AnalyticsPerformanceVO getPerformance(Integer days) {
        return getDashboard(days).getPerformance();
    }

    private AnalyticsDashboardVO buildDashboard(Long tenantId, int days) {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days - 1L);
        LocalDateTime startTime = startDate.atStartOfDay();
        LocalDateTime endTime = endDate.plusDays(1L).atStartOfDay();

        Map<LocalDate, DailyAccumulator> dailyMap = initDailyMap(startDate, endDate);
        mergeSessionRows(dailyMap, analyticsRawStatsMapper.selectSessionCreatedDailyRows(tenantId, startTime, endTime), true);
        mergeSessionRows(dailyMap, analyticsRawStatsMapper.selectSessionClosedDailyRows(tenantId, startTime, endTime), false);
        mergeMessageRows(dailyMap, analyticsRawStatsMapper.selectMessageDailyRows(tenantId, startTime, endTime));
        mergeAgentRows(dailyMap, analyticsRawStatsMapper.selectAgentDailyRows(tenantId, startTime, endTime));
        mergeAggregateRows(dailyMap, analyticsDailyStatsMapper.selectList(new LambdaQueryWrapper<AnalyticsDailyStats>()
                .eq(AnalyticsDailyStats::getTenantId, tenantId)
                .ge(AnalyticsDailyStats::getStatDate, startDate)
                .le(AnalyticsDailyStats::getStatDate, endDate)));

        AnalyticsDashboardVO dashboard = new AnalyticsDashboardVO();
        dashboard.setTrends(buildTrends(dailyMap));
        dashboard.setIntents(buildIntentStats(analyticsRawStatsMapper.selectIntentStats(tenantId, startTime, endTime)));
        dashboard.setSources(buildSourceStats(analyticsRawStatsMapper.selectSourceStats(tenantId, startTime, endTime)));
        dashboard.setOverview(buildOverview(days, startDate, endDate, dailyMap, analyticsRawStatsMapper.countUniqueUsers(tenantId, startTime, endTime)));
        dashboard.setPerformance(buildPerformance(dailyMap));
        return dashboard;
    }

    private Map<LocalDate, DailyAccumulator> initDailyMap(LocalDate startDate, LocalDate endDate) {
        Map<LocalDate, DailyAccumulator> dailyMap = new LinkedHashMap<>();
        LocalDate cursor = startDate;
        while (!cursor.isAfter(endDate)) {
            dailyMap.put(cursor, new DailyAccumulator(cursor));
            cursor = cursor.plusDays(1);
        }
        return dailyMap;
    }

    private void mergeSessionRows(Map<LocalDate, DailyAccumulator> dailyMap, List<DailySessionRow> rows, boolean created) {
        if (rows == null) {
            return;
        }
        for (DailySessionRow row : rows) {
            DailyAccumulator accumulator = dailyMap.get(row.getStatDate());
            if (accumulator == null) {
                continue;
            }
            if (created) {
                accumulator.sessionsCreated = safeLong(row.getTotalCount());
            } else {
                accumulator.sessionsClosed = safeLong(row.getTotalCount());
            }
        }
    }

    private void mergeMessageRows(Map<LocalDate, DailyAccumulator> dailyMap, List<DailyMessageRow> rows) {
        if (rows == null) {
            return;
        }
        for (DailyMessageRow row : rows) {
            DailyAccumulator accumulator = dailyMap.get(row.getStatDate());
            if (accumulator == null) {
                continue;
            }
            accumulator.messagesTotal = safeLong(row.getMessagesTotal());
            accumulator.userMessages = safeLong(row.getUserMessages());
            accumulator.assistantMessages = safeLong(row.getAssistantMessages());
            accumulator.knowledgeAnswers = safeLong(row.getKnowledgeAnswers());
            accumulator.agentAnswers = safeLong(row.getAgentAnswers());
            accumulator.referencesTotal = safeLong(row.getReferencesTotal());
            accumulator.totalTokens = safeLong(row.getTotalTokens());
            accumulator.totalDurationMs = safeLong(row.getTotalDurationMs());
            accumulator.escalationCount = Math.max(accumulator.escalationCount, safeLong(row.getEscalationCount()));
        }
    }

    private void mergeAgentRows(Map<LocalDate, DailyAccumulator> dailyMap, List<DailyAgentRow> rows) {
        if (rows == null) {
            return;
        }
        for (DailyAgentRow row : rows) {
            DailyAccumulator accumulator = dailyMap.get(row.getStatDate());
            if (accumulator == null) {
                continue;
            }
            accumulator.agentCalls = safeLong(row.getAgentCalls());
            accumulator.agentSuccessCalls = safeLong(row.getAgentSuccessCalls());
            accumulator.agentFailedCalls = safeLong(row.getAgentFailedCalls());
        }
    }

    private void mergeAggregateRows(Map<LocalDate, DailyAccumulator> dailyMap, List<AnalyticsDailyStats> rows) {
        if (rows == null) {
            return;
        }
        for (AnalyticsDailyStats row : rows) {
            DailyAccumulator accumulator = dailyMap.get(row.getStatDate());
            if (accumulator == null) {
                continue;
            }
            accumulator.groundedAnswers = Math.max(accumulator.groundedAnswers, safeLong(row.getGroundedAnswers()));
            accumulator.escalationCount = Math.max(accumulator.escalationCount, safeLong(row.getEscalationCount()));
            accumulator.agentCalls = Math.max(accumulator.agentCalls, safeLong(row.getAgentCalls()));
            accumulator.agentSuccessCalls = Math.max(accumulator.agentSuccessCalls, safeLong(row.getAgentSuccessCalls()));
            accumulator.agentFailedCalls = Math.max(accumulator.agentFailedCalls, safeLong(row.getAgentFailedCalls()));
        }
    }

    private List<AnalyticsTrendPointVO> buildTrends(Map<LocalDate, DailyAccumulator> dailyMap) {
        List<AnalyticsTrendPointVO> trends = new ArrayList<>(dailyMap.size());
        for (DailyAccumulator accumulator : dailyMap.values()) {
            AnalyticsTrendPointVO point = new AnalyticsTrendPointVO();
            point.setStatDate(accumulator.statDate);
            point.setSessionsCreated(accumulator.sessionsCreated);
            point.setSessionsClosed(accumulator.sessionsClosed);
            point.setMessagesTotal(accumulator.messagesTotal);
            point.setKnowledgeAnswers(accumulator.knowledgeAnswers);
            point.setAgentAnswers(accumulator.agentAnswers);
            point.setTotalTokens(accumulator.totalTokens);
            trends.add(point);
        }
        return trends;
    }

    private AnalyticsOverviewVO buildOverview(int days,
                                              LocalDate startDate,
                                              LocalDate endDate,
                                              Map<LocalDate, DailyAccumulator> dailyMap,
                                              Long uniqueUsers) {
        AnalyticsOverviewVO overview = new AnalyticsOverviewVO();
        overview.setDays(days);
        overview.setStartDate(startDate);
        overview.setEndDate(endDate);
        overview.setUniqueUsers(safeLong(uniqueUsers));

        long totalSessions = 0L;
        long totalMessages = 0L;
        long userMessages = 0L;
        long assistantMessages = 0L;
        long knowledgeAnswers = 0L;
        long agentAnswers = 0L;
        long agentCalls = 0L;
        long agentSuccessCalls = 0L;
        long agentFailedCalls = 0L;
        long totalTokens = 0L;
        long totalDurationMs = 0L;

        for (DailyAccumulator accumulator : dailyMap.values()) {
            totalSessions += accumulator.sessionsCreated;
            totalMessages += accumulator.messagesTotal;
            userMessages += accumulator.userMessages;
            assistantMessages += accumulator.assistantMessages;
            knowledgeAnswers += accumulator.knowledgeAnswers;
            agentAnswers += accumulator.agentAnswers;
            agentCalls += accumulator.agentCalls;
            agentSuccessCalls += accumulator.agentSuccessCalls;
            agentFailedCalls += accumulator.agentFailedCalls;
            totalTokens += accumulator.totalTokens;
            totalDurationMs += accumulator.totalDurationMs;
        }

        overview.setTotalSessions(totalSessions);
        overview.setTotalMessages(totalMessages);
        overview.setUserMessages(userMessages);
        overview.setAssistantMessages(assistantMessages);
        overview.setKnowledgeAnswers(knowledgeAnswers);
        overview.setAgentAnswers(agentAnswers);
        overview.setAgentCalls(agentCalls);
        overview.setAgentSuccessCalls(agentSuccessCalls);
        overview.setAgentFailedCalls(agentFailedCalls);
        overview.setTotalTokens(totalTokens);
        overview.setAvgDurationMs(divide(totalDurationMs, assistantMessages));
        return overview;
    }

    private AnalyticsPerformanceVO buildPerformance(Map<LocalDate, DailyAccumulator> dailyMap) {
        long assistantAnswers = 0L;
        long totalTokens = 0L;
        long totalDurationMs = 0L;
        long totalReferences = 0L;
        long groundedAnswers = 0L;
        long escalationCount = 0L;
        long agentCalls = 0L;
        long agentSuccessCalls = 0L;

        for (DailyAccumulator accumulator : dailyMap.values()) {
            assistantAnswers += accumulator.assistantMessages;
            totalTokens += accumulator.totalTokens;
            totalDurationMs += accumulator.totalDurationMs;
            totalReferences += accumulator.referencesTotal;
            groundedAnswers += accumulator.groundedAnswers;
            escalationCount += accumulator.escalationCount;
            agentCalls += accumulator.agentCalls;
            agentSuccessCalls += accumulator.agentSuccessCalls;
        }

        AnalyticsPerformanceVO performance = new AnalyticsPerformanceVO();
        performance.setTotalTokens(totalTokens);
        performance.setAvgTokensPerAnswer(divide(totalTokens, assistantAnswers));
        performance.setAvgDurationMs(divide(totalDurationMs, assistantAnswers));
        performance.setAvgReferencesPerAnswer(divide(totalReferences, assistantAnswers));
        performance.setGroundedAnswers(groundedAnswers);
        performance.setEscalationCount(escalationCount);
        performance.setAgentSuccessRate(divide(agentSuccessCalls * 100L, agentCalls));
        return performance;
    }

    private List<AnalyticsIntentStatVO> buildIntentStats(List<NameCountRow> rows) {
        List<AnalyticsIntentStatVO> items = new ArrayList<>();
        if (rows == null) {
            return items;
        }
        for (NameCountRow row : rows) {
            AnalyticsIntentStatVO item = new AnalyticsIntentStatVO();
            item.setIntent(row.getName());
            item.setHitCount(safeLong(row.getTotalCount()));
            items.add(item);
        }
        return items;
    }

    private List<AnalyticsSourceStatVO> buildSourceStats(List<NameCountRow> rows) {
        List<AnalyticsSourceStatVO> items = new ArrayList<>();
        if (rows == null) {
            return items;
        }
        for (NameCountRow row : rows) {
            AnalyticsSourceStatVO item = new AnalyticsSourceStatVO();
            item.setSource(row.getName());
            item.setHitCount(safeLong(row.getTotalCount()));
            items.add(item);
        }
        return items;
    }

    private int normalizeDays(Integer days) {
        int value = days == null ? analyticsProperties.getQuery().getDefaultDays() : days;
        if (value < 1) {
            value = analyticsProperties.getQuery().getDefaultDays();
        }
        return Math.min(value, analyticsProperties.getQuery().getMaxDays());
    }

    private long safeLong(Long value) {
        return value == null ? 0L : value;
    }

    private double divide(long numerator, long denominator) {
        if (denominator <= 0L) {
            return 0D;
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private static class DailyAccumulator {
        private final LocalDate statDate;
        private long sessionsCreated;
        private long sessionsClosed;
        private long messagesTotal;
        private long userMessages;
        private long assistantMessages;
        private long knowledgeAnswers;
        private long agentAnswers;
        private long groundedAnswers;
        private long referencesTotal;
        private long totalTokens;
        private long totalDurationMs;
        private long agentCalls;
        private long agentSuccessCalls;
        private long agentFailedCalls;
        private long escalationCount;

        private DailyAccumulator(LocalDate statDate) {
            this.statDate = statDate;
        }
    }
}
