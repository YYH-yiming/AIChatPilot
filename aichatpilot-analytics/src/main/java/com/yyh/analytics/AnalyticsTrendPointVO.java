package com.yyh.analytics;

import lombok.Data;

import java.time.LocalDate;

@Data
public class AnalyticsTrendPointVO {

    private LocalDate statDate;
    private Long sessionsCreated;
    private Long sessionsClosed;
    private Long messagesTotal;
    private Long knowledgeAnswers;
    private Long agentAnswers;
    private Long totalTokens;
}
