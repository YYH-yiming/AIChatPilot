package com.yyh.agent.llm;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AgentLlmResult {
    private String content;
    private Integer tokenUsed;
}
