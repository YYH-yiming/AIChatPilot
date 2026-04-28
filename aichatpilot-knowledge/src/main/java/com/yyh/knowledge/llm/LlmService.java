package com.yyh.knowledge.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.yyh.common.exception.BusinessException;
import com.yyh.common.result.ResultCode;
import org.springframework.beans.factory.annotation.Value;
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
public class LlmService {

    @Value("${llm.provider:openai-compatible}")
    private String provider;

    @Value("${llm.api-url}")
    private String apiUrl;

    @Value("${llm.api-key:}")
    private String apiKey;

    @Value("${llm.model:}")
    private String model;

    @Value("${llm.openai-compatible:true}")
    private boolean openAiCompatible;

    @Value("${llm.temperature:0.3}")
    private double temperature;

    @Value("${llm.max-tokens:2048}")
    private int maxTokens;

    public String chat(String systemPrompt, String userPrompt) {
        validateConfig();

        if (!openAiCompatible) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "当前仅支持OpenAI兼容的LLM接口协议");
        }

        RestClient client = RestClient.builder()
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("messages", buildMessages(systemPrompt, userPrompt));
        request.put("temperature", temperature);
        request.put("max_tokens", maxTokens);
        request.put("stream", false);

        JsonNode response = client.post()
                .uri(apiUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(JsonNode.class);

        String answer = extractContent(response);
        if (!StringUtils.hasText(answer)) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "LLM未返回有效回答");
        }
        return answer.trim();
    }

    public String currentModel() {
        return model;
    }

    public String currentProvider() {
        return provider;
    }

    private void validateConfig() {
        if (!StringUtils.hasText(apiKey)) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "缺少LLM_API_KEY配置");
        }
        if (!StringUtils.hasText(apiUrl)) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "缺少LLM_API_URL配置");
        }
        if (!StringUtils.hasText(model)) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "缺少LLM_MODEL配置");
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
