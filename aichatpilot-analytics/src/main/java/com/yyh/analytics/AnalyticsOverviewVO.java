package com.yyh.analytics;

import lombok.Data;

import java.time.LocalDate;

@Data
public class AnalyticsOverviewVO {

    private Integer days;
    private LocalDate startDate;
    private LocalDate endDate;
    private Long totalSessions;
    private Long totalMessages;
    private Long userMessages;
    private Long assistantMessages;
    private Long uniqueUsers;
    private Long knowledgeAnswers;
    private Long agentAnswers;
    private Long agentCalls;
    private Long agentSuccessCalls;
    private Long agentFailedCalls;
    private Long totalTokens;
    private Double avgDurationMs;
}
