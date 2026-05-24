package com.yyh.agent.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyh.agent.config.AnalyticsEventProperties;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentAnalyticsEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final AnalyticsEventProperties analyticsEventProperties;

    public void publish(AgentTraceService.TraceRecord record) {
        if (!analyticsEventProperties.isEnabled() || record == null || record.getTenantId() == null) {
            return;
        }

        AgentTraceEvent event = new AgentTraceEvent();
        event.setTenantId(record.getTenantId());
        event.setSessionId(record.getSessionId());
        event.setMessageId(record.getMessageId());
        event.setAgentName(record.getAgentName());
        event.setIntent(record.getIntent());
        event.setStatus(record.getStatus());
        event.setTokenUsed(record.getTokenUsed());
        event.setDurationMs(record.getDurationMs());
        event.setEventTime(LocalDateTime.now());

        try {
            kafkaTemplate.send(
                    analyticsEventProperties.getAgentTraceTopic(),
                    String.valueOf(record.getTenantId()),
                    objectMapper.writeValueAsString(event)
            );
        } catch (Exception ex) {
            log.warn("发送Agent Analytics事件失败: tenantId={}, agent={}, message={}",
                    record.getTenantId(), record.getAgentName(), ex.getMessage());
        }
    }

    @Data
    private static class AgentTraceEvent {
        private Long tenantId;
        private Long sessionId;
        private Long messageId;
        private String agentName;
        private String intent;
        private String status;
        private Integer tokenUsed;
        private Integer durationMs;
        private LocalDateTime eventTime;
    }
}
