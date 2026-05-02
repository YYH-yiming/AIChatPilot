package com.yyh.agent.agent;

import lombok.Getter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Getter
public class AgentExecutionContext {

    private final List<String> toolsCalled = new ArrayList<>();
    private final Map<String, Object> toolResults = new LinkedHashMap<>();
    private int tokenUsed;

    public void addTool(String tool) {
        toolsCalled.add(tool);
    }

    public void addToolResult(String key, Object value) {
        toolResults.put(key, value);
    }

    public void addTokenUsed(int tokens) {
        tokenUsed += Math.max(tokens, 0);
    }
}
