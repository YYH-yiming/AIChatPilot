package com.yyh.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "analytics.events", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AnalyticsEventConsumer {

    private final ObjectMapper objectMapper;
    private final AnalyticsAggregationService analyticsAggregationService;

    @KafkaListener(topics = "${analytics.events.chat-session-topic:analytics.chat.session}")
    public void consumeChatSession(String payload) {
        consume(payload, ChatSessionAnalyticsEvent.class, analyticsAggregationService::consumeChatSession);
    }

    @KafkaListener(topics = "${analytics.events.chat-message-topic:analytics.chat.message}")
    public void consumeChatMessage(String payload) {
        consume(payload, ChatMessageAnalyticsEvent.class, analyticsAggregationService::consumeChatMessage);
    }

    @KafkaListener(topics = "${analytics.events.agent-trace-topic:analytics.agent.trace}")
    public void consumeAgentTrace(String payload) {
        consume(payload, AgentTraceAnalyticsEvent.class, analyticsAggregationService::consumeAgentTrace);
    }

    private <T> void consume(String payload, Class<T> type, java.util.function.Consumer<T> handler) {
        try {
            handler.accept(objectMapper.readValue(payload, type));
        } catch (Exception ex) {
            log.warn("消费Analytics事件失败: type={}, message={}", type.getSimpleName(), ex.getMessage());
        }
    }
}
