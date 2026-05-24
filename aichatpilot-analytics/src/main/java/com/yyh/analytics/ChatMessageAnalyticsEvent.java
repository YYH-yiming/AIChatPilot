package com.yyh.analytics;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ChatMessageAnalyticsEvent {

    private Long tenantId;
    private Long userId;
    private Long sessionId;
    private Long messageId;
    private String role;
    private String answerSource;
    private String intent;
    private Long kbId;
    private Integer tokenUsed;
    private Long durationMs;
    private Integer referenceCount;
    private Boolean grounded;
    private LocalDateTime eventTime;
}
