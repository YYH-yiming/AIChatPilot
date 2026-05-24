package com.yyh.analytics;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AgentTraceAnalyticsEvent {

    private Long tenantId;
    private Long sessionId;
    private Long messageId;
    private String agentName;
    private String intent;
    private String status;
    private Integer tokenUsed;
    private Integer durationMs;
    private LocalDateTime eventTime;
}
