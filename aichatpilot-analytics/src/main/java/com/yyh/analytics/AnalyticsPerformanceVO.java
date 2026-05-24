package com.yyh.analytics;

import lombok.Data;

@Data
public class AnalyticsPerformanceVO {

    private Long totalTokens;
    private Double avgTokensPerAnswer;
    private Double avgDurationMs;
    private Double avgReferencesPerAnswer;
    private Long groundedAnswers;
    private Long escalationCount;
    private Double agentSuccessRate;
}
