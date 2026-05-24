package com.yyh.chat.client.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AgentChatClientResponse {

    private String answer;
    private String agentName;
    private List<String> toolsCalled = new ArrayList<>();
    private Integer tokenUsed;
    private Long durationMs;
    private String intent;
    private Double confidence;
    private Long kbId;
    private Long sessionId;
    private List<AgentKnowledgeReferenceClientResponse> references = new ArrayList<>();
}
