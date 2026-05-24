package com.yyh.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.yyh.common.exception.BusinessException;
import com.yyh.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(AgentLlmProperties.class)
public class AgentLlmService {

    private final AgentLlmProperties properties;

    public AgentLlmResult chat(String systemPrompt, String userPrompt) {
        List<String> errors = new ArrayList<>();
        for (AgentLlmProperties.Provider provider : properties.orderedProviders()) {
            try {
                return callProvider(provider, systemPrompt, userPrompt);
            } catch (Exception ex) {
                String providerName = provider.displayName();
                log.warn("Agent LLM Provider调用失败，将尝试下一个Provider: provider={}, message={}",
                        providerName, ex.getMessage());
                errors.add(providerName + " -> " + ex.getMessage());
            }
        }
        throw new BusinessException(ResultCode.INTERNAL_ERROR,
                "所有Agent LLM Provider都调用失败: " + String.join(" | ", errors));
    }

    private AgentLlmResult callProvider(AgentLlmProperties.Provider provider, String systemPrompt, String userPrompt) {
        validateProvider(provider);
        if (!provider.isOpenaiCompatible()) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "当前Agent模块仅支持OpenAI兼容的聊天接口");
        }

        RestClient client = RestClient.builder()
                .requestFactory(requestFactory(provider))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + provider.getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", provider.getModel());
        request.put("messages", buildMessages(systemPrompt, userPrompt));
        request.put("temperature", provider.getTemperature());
        request.put("max_tokens", provider.getMaxTokens());
        request.put("stream", false);

        JsonNode response = client.post()
                .uri(provider.getApiUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(JsonNode.class);

        String answer = extractContent(response);
        if (!StringUtils.hasText(answer)) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "Agent LLM未返回有效回答");
        }
        return new AgentLlmResult(answer.trim(), response == null ? 0 : response.path("usage").path("total_tokens").asInt(0));
    }

    public String currentModel() {
        return properties.getModel();
    }

    private void validateProvider(AgentLlmProperties.Provider provider) {
        if (!StringUtils.hasText(provider.getApiKey())) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "缺少Agent LLM Provider的apiKey配置");
        }
        if (!StringUtils.hasText(provider.getApiUrl())) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "缺少Agent LLM Provider的apiUrl配置");
        }
        if (!StringUtils.hasText(provider.getModel())) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "缺少Agent LLM Provider的model配置");
        }
    }

    private SimpleClientHttpRequestFactory requestFactory(AgentLlmProperties.Provider provider) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(provider.getConnectTimeoutMs());
        requestFactory.setReadTimeout(provider.getReadTimeoutMs());
        return requestFactory;
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
