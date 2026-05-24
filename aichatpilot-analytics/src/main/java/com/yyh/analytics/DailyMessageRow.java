package com.yyh.analytics;

import lombok.Data;

import java.time.LocalDate;

@Data
public class DailyMessageRow {

    private LocalDate statDate;
    private Long messagesTotal;
    private Long userMessages;
    private Long assistantMessages;
    private Long knowledgeAnswers;
    private Long agentAnswers;
    private Long referencesTotal;
    private Long totalTokens;
    private Long totalDurationMs;
    private Long escalationCount;
}
