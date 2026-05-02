package com.yyh.agent.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AgentResponse {

    private String answer;
    private String agentName;
    private List<String> toolsCalled = new ArrayList<>();
    private Integer tokenUsed = 0;
    private Long durationMs = 0L;
    private String intent;
    private Double confidence;
    private Long kbId;
    private Long sessionId;
    private List<KnowledgeReference> references = new ArrayList<>();
}
