package com.yyh.agent.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TicketInfo {
    private Long ticketId;
    private Long tenantId;
    private Long sessionId;
    private Long userId;
    private String type;
    private Integer priority;
    private String status;
    private String description;
    private LocalDateTime createdAt;
}
