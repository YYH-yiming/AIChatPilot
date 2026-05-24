package com.yyh.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyh.chat.config.AnalyticsEventProperties;
import com.yyh.chat.dto.ChatReplyVO;
import com.yyh.chat.entity.ChatMessage;
import com.yyh.chat.entity.ChatSession;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatAnalyticsEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final AnalyticsEventProperties analyticsEventProperties;

    public void publishSessionCreated(ChatSession session) {
        ChatSessionEvent event = new ChatSessionEvent();
        event.setTenantId(session.getTenantId());
        event.setUserId(session.getUserId());
        event.setSessionId(session.getId());
        event.setMode(session.getMode());
        event.setKbId(session.getKbId());
        event.setEventType("created");
        event.setEventTime(session.getCreatedAt() == null ? LocalDateTime.now() : session.getCreatedAt());
        publish(analyticsEventProperties.getChatSessionTopic(), session.getTenantId(), event);
    }

    public void publishSessionClosed(ChatSession session) {
        ChatSessionEvent event = new ChatSessionEvent();
        event.setTenantId(session.getTenantId());
        event.setUserId(session.getUserId());
        event.setSessionId(session.getId());
        event.setMode(session.getMode());
        event.setKbId(session.getKbId());
        event.setEventType("closed");
        event.setEventTime(LocalDateTime.now());
        publish(analyticsEventProperties.getChatSessionTopic(), session.getTenantId(), event);
    }

    public void publishUserMessage(ChatMessage message) {
        publishMessage(message, null);
    }

    public void publishAssistantMessage(ChatMessage message, ChatReplyVO reply) {
        publishMessage(message, reply);
    }

    private void publishMessage(ChatMessage message, ChatReplyVO reply) {
        ChatMessageEvent event = new ChatMessageEvent();
        event.setTenantId(message.getTenantId());
        event.setUserId(message.getUserId());
        event.setSessionId(message.getSessionId());
        event.setMessageId(message.getId());
        event.setRole(message.getRole());
        event.setAnswerSource(message.getAnswerSource());
        event.setIntent(message.getIntent());
        event.setKbId(message.getKbId());
        event.setTokenUsed(message.getTokenUsed());
        event.setDurationMs(message.getDurationMs());
        event.setReferenceCount(reply == null ? 0 : reply.getReferenceCount());
        event.setGrounded(reply != null && Boolean.TRUE.equals(reply.getGrounded()));
        event.setEventTime(message.getCreatedAt() == null ? LocalDateTime.now() : message.getCreatedAt());
        publish(analyticsEventProperties.getChatMessageTopic(), message.getTenantId(), event);
    }

    private void publish(String topic, Long tenantId, Object event) {
        if (!analyticsEventProperties.isEnabled()) {
            return;
        }
        try {
            kafkaTemplate.send(topic, String.valueOf(tenantId), objectMapper.writeValueAsString(event));
        } catch (Exception ex) {
            log.warn("发送Chat Analytics事件失败: topic={}, tenantId={}, message={}", topic, tenantId, ex.getMessage());
        }
    }

    @Data
    private static class ChatSessionEvent {
        private Long tenantId;
        private Long userId;
        private Long sessionId;
        private String mode;
        private Long kbId;
        private String eventType;
        private LocalDateTime eventTime;
    }

    @Data
    private static class ChatMessageEvent {
        private Long tenantId;
        private Long userId;
        private Long sessionId;
        private Long messageId;
        private String role;
        private String answerSource;
        private String intent;
        private Long kbId;
        private Integer tokenUsed;
        private Long durationMs;
        private Integer referenceCount;
        private Boolean grounded;
        private LocalDateTime eventTime;
    }
}
