package com.yyh.chat.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.yyh.chat.config.ChatLlmProperties;
import com.yyh.common.exception.BusinessException;
import com.yyh.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ChatLlmService {

    private final ChatLlmProperties properties;

    public String chat(String systemPrompt, String userPrompt) {
        validateConfig();
        if (!properties.isOpenaiCompatible()) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "当前Chat模块仅支持OpenAI兼容的聊天接口");
        }

        RestClient client = RestClient.builder()
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", properties.getModel());
        request.put("messages", buildMessages(systemPrompt, userPrompt));
        request.put("temperature", properties.getTemperature());
        request.put("max_tokens", properties.getMaxTokens());
        request.put("stream", false);

        JsonNode response = client.post()
                .uri(properties.getApiUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(JsonNode.class);

        String answer = extractContent(response);
        if (!StringUtils.hasText(answer)) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "Chat LLM未返回有效回答");
        }
        return answer.trim();
    }

    private void validateConfig() {
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "缺少CHAT_LLM_API_KEY或CHAT_API_KEY或LLM_API_KEY配置");
        }
        if (!StringUtils.hasText(properties.getApiUrl())) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "缺少CHAT_LLM_API_URL或CHAT_BASE_URL或LLM_API_URL配置");
        }
        if (!StringUtils.hasText(properties.getModel())) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "缺少CHAT_LLM_MODEL或CHAT_MODEL或LLM_MODEL配置");
        }
    }

    private List<Map<String, Object>> buildMessages(String systemPrompt, String userPrompt) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        messages.add(Map.of("role", "user", "content", userPrompt));
        return messages;
    }

    private String extractContent(JsonNode response) {
        if (response == null) {
            return null;
        }
        JsonNode choices = response.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return null;
        }
        JsonNode contentNode = choices.get(0).path("message").path("content");
        if (contentNode.isTextual()) {
            return contentNode.asText();
        }
        if (contentNode.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode item : contentNode) {
                if (item.isTextual()) {
                    builder.append(item.asText());
                    continue;
                }
                JsonNode textNode = item.path("text");
                if (textNode.isTextual()) {
                    builder.append(textNode.asText());
                }
            }
            return builder.toString();
        }
        return null;
    }
}
