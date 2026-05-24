package com.yyh.chat.client.dto;

import lombok.Data;

@Data
public class AgentChatClientRequest {

    private String query;
    private Long sessionId;
    private Long kbId;
    private Long tenantId;
}
