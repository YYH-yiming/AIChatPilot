package com.yyh.agent.trace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyh.agent.entity.AgentTrace;
import com.yyh.agent.mapper.AgentTraceMapper;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTraceService {

    private final AgentTraceMapper agentTraceMapper;
    private final ObjectMapper objectMapper;
    private final AgentAnalyticsEventPublisher agentAnalyticsEventPublisher;

    public void record(TraceRecord record) {
        try {
            AgentTrace trace = new AgentTrace();
            trace.setSessionId(record.getSessionId() == null ? 0L : record.getSessionId());
            trace.setMessageId(record.getMessageId());
            trace.setAgentName(record.getAgentName());
            trace.setParentTraceId(record.getParentTraceId());
            trace.setInputText(record.getInputText());
            trace.setOutputText(record.getOutputText());
            trace.setToolsCalled(toJson(record.getToolsCalled()));
            trace.setToolResults(toJson(record.getToolResults()));
            trace.setTokenUsed(record.getTokenUsed());
            trace.setDurationMs(record.getDurationMs());
            trace.setStatus(record.getStatus());
            trace.setErrorMsg(record.getErrorMsg());
            agentTraceMapper.insert(trace);
        } catch (Exception ex) {
            log.warn("记录Agent Trace失败: agent={}, message={}", record.getAgentName(), ex.getMessage());
        }
        agentAnalyticsEventPublisher.publish(record);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "[]";
        }
    }

    @Data
    @Builder
    public static class TraceRecord {
        private Long tenantId;
        private Long sessionId;
        private Long messageId;
        private String agentName;
        private String intent;
        private Long parentTraceId;
        private String inputText;
        private String outputText;
        private Object toolsCalled;
        private Object toolResults;
        private Integer tokenUsed;
        private Integer durationMs;
        private String status;
        private String errorMsg;
    }
}
