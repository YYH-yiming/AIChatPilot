package com.yyh.chat.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyh.chat.config.ChatProperties;
import com.yyh.chat.entity.ChatMessage;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatMemoryService {

    private static final TypeReference<List<ConversationMessage>> MESSAGE_TYPE = new TypeReference<>() {
    };

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final ChatProperties chatProperties;

    public List<ConversationMessage> loadRecentMessages(Long sessionId, int limit) {
        if (!chatProperties.getMemory().isEnabled()) {
            return List.of();
        }
        try {
            String value = stringRedisTemplate.opsForValue().get(buildKey(sessionId));
            if (!StringUtils.hasText(value)) {
                return List.of();
            }
            List<ConversationMessage> messages = new ArrayList<>(objectMapper.readValue(value, MESSAGE_TYPE));
            if (messages.size() <= limit) {
                return messages;
            }
            return new ArrayList<>(messages.subList(messages.size() - limit, messages.size()));
        } catch (JsonProcessingException ex) {
            log.warn("解析Chat上下文缓存失败: sessionId={}, message={}", sessionId, ex.getMessage());
            return List.of();
        } catch (Exception ex) {
            log.warn("读取Chat上下文缓存失败: sessionId={}, message={}", sessionId, ex.getMessage());
            return List.of();
        }
    }

    public void appendMessage(ChatMessage message) {
        if (!chatProperties.getMemory().isEnabled() || message == null || !StringUtils.hasText(message.getContent())) {
            return;
        }
        try {
            Long sessionId = message.getSessionId();
            List<ConversationMessage> messages = new ArrayList<>(loadRecentMessages(sessionId, Integer.MAX_VALUE));
            messages.add(new ConversationMessage(message.getRole(), message.getContent(), message.getCreatedAt()));
            int maxMessages = Math.max(chatProperties.getMemory().getMaxMessages(), 4);
            if (messages.size() > maxMessages) {
                messages = new ArrayList<>(messages.subList(messages.size() - maxMessages, messages.size()));
            }
            stringRedisTemplate.opsForValue().set(
                    buildKey(sessionId),
                    objectMapper.writeValueAsString(messages),
                    Duration.ofHours(Math.max(chatProperties.getMemory().getTtlHours(), 1L))
            );
        } catch (Exception ex) {
            log.warn("写入Chat上下文缓存失败: sessionId={}, message={}", message.getSessionId(), ex.getMessage());
        }
    }

    private String buildKey(Long sessionId) {
        return "chat:session:messages:" + sessionId;
    }

    @Data
    @AllArgsConstructor
    public static class ConversationMessage {
        private String role;
        private String content;
        private LocalDateTime createdAt;
    }
}
