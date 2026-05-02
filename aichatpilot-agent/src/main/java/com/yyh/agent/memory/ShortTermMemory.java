package com.yyh.agent.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyh.agent.config.AgentProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ShortTermMemory {

    private static final TypeReference<List<MemoryMessage>> MESSAGE_TYPE = new TypeReference<>() {
    };

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final AgentProperties agentProperties;

    public String buildConversationContext(Long sessionId, Long userId) {
        List<MemoryMessage> messages = listMessages(sessionId, userId);
        if (CollectionUtils.isEmpty(messages)) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (MemoryMessage message : messages) {
            builder.append(message.getRole()).append(": ").append(message.getContent()).append("\n");
        }
        return builder.toString().trim();
    }

    public void appendUserMessage(Long sessionId, Long userId, String content) {
        appendMessage(sessionId, userId, "user", content);
    }

    public void appendAssistantMessage(Long sessionId, Long userId, String content) {
        appendMessage(sessionId, userId, "assistant", content);
    }

    private void appendMessage(Long sessionId, Long userId, String role, String content) {
        if (!agentProperties.getMemory().isEnabled() || !StringUtils.hasText(content)) {
            return;
        }
        try {
            String key = buildKey(sessionId, userId);
            List<MemoryMessage> messages = listMessages(sessionId, userId);
            messages.add(new MemoryMessage(role, content, LocalDateTime.now()));
            int maxMessages = Math.max(agentProperties.getMemory().getMaxMessages(), 2);
            if (messages.size() > maxMessages) {
                messages = new ArrayList<>(messages.subList(messages.size() - maxMessages, messages.size()));
            }
            stringRedisTemplate.opsForValue().set(
                    key,
                    objectMapper.writeValueAsString(messages),
                    Duration.ofHours(Math.max(agentProperties.getMemory().getTtlHours(), 1L))
            );
        } catch (Exception ex) {
            log.warn("写入短期记忆失败: sessionId={}, userId={}, message={}", sessionId, userId, ex.getMessage());
        }
    }

    private List<MemoryMessage> listMessages(Long sessionId, Long userId) {
        if (!agentProperties.getMemory().isEnabled()) {
            return List.of();
        }
        try {
            String value = stringRedisTemplate.opsForValue().get(buildKey(sessionId, userId));
            if (!StringUtils.hasText(value)) {
                return new ArrayList<>();
            }
            return new ArrayList<>(objectMapper.readValue(value, MESSAGE_TYPE));
        } catch (JsonProcessingException ex) {
            log.warn("解析短期记忆失败: sessionId={}, userId={}, message={}", sessionId, userId, ex.getMessage());
            return new ArrayList<>();
        } catch (Exception ex) {
            log.warn("读取短期记忆失败: sessionId={}, userId={}, message={}", sessionId, userId, ex.getMessage());
            return new ArrayList<>();
        }
    }

    private String buildKey(Long sessionId, Long userId) {
        if (sessionId != null && sessionId > 0) {
            return "agent:memory:session:" + sessionId;
        }
        return "agent:memory:user:" + userId;
    }

    @Data
    @AllArgsConstructor
    public static class MemoryMessage {
        private String role;
        private String content;
        private LocalDateTime createdAt;
    }
}
