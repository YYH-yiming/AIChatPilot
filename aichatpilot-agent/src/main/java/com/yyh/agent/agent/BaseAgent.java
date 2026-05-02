package com.yyh.agent.agent;

import com.yyh.agent.dto.AgentRequest;
import com.yyh.agent.dto.AgentResponse;
import com.yyh.agent.trace.AgentTraceService;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public abstract class BaseAgent {

    private final AgentTraceService agentTraceService;

    public AgentResponse execute(AgentRequest request) {
        long start = System.currentTimeMillis();
        AgentExecutionContext context = new AgentExecutionContext();
        try {
            beforeExecute(request, context);
            AgentResponse response = doExecute(request, context);
            response.setAgentName(agentName());
            response.setToolsCalled(context.getToolsCalled());
            response.setTokenUsed(context.getTokenUsed());
            response.setDurationMs(System.currentTimeMillis() - start);
            agentTraceService.record(AgentTraceService.TraceRecord.builder()
                    .sessionId(request.getSessionId())
                    .agentName(agentName())
                    .inputText(request.getQuery())
                    .outputText(response.getAnswer())
                    .toolsCalled(context.getToolsCalled())
                    .toolResults(context.getToolResults())
                    .tokenUsed(context.getTokenUsed())
                    .durationMs((int) response.getDurationMs().longValue())
                    .status("success")
                    .build());
            return response;
        } catch (Exception ex) {
            agentTraceService.record(AgentTraceService.TraceRecord.builder()
                    .sessionId(request.getSessionId())
                    .agentName(agentName())
                    .inputText(request.getQuery())
                    .outputText(null)
                    .toolsCalled(context.getToolsCalled())
                    .toolResults(context.getToolResults())
                    .tokenUsed(context.getTokenUsed())
                    .durationMs((int) (System.currentTimeMillis() - start))
                    .status("failed")
                    .errorMsg(ex.getMessage())
                    .build());
            throw ex;
        }
    }

    protected void beforeExecute(AgentRequest request, AgentExecutionContext context) {
    }

    protected abstract AgentResponse doExecute(AgentRequest request, AgentExecutionContext context);

    protected abstract String agentName();
}
