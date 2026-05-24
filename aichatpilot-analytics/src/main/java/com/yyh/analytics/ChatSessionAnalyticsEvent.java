package com.yyh.analytics;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ChatSessionAnalyticsEvent {

    private Long tenantId;
    private Long userId;
    private Long sessionId;
    private String mode;
    private Long kbId;
    private String eventType;
    private LocalDateTime eventTime;
}
