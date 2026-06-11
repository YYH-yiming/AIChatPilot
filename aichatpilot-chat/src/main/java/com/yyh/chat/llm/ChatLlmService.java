package com.yyh.chat.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.yyh.chat.config.ChatLlmProperties;
import com.yyh.common.exception.BusinessException;
import com.yyh.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatLlmService {

    private final ChatLlmProperties properties;

    // 统一实现开关：jdk=原 RestClient（默认）；langchain4j=LC4j OpenAiChatModel。
    @Value("${llm.stream-impl:jdk}")
    private String streamImpl;

    private volatile OpenAiChatModel lc4jChatModel;

    public String chat(String systemPrompt, String userPrompt) {
        validateConfig();
        if (!properties.isOpenaiCompatible()) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "当前Chat模块仅支持OpenAI兼容的聊天接口");
        }
        if ("langchain4j".equalsIgnoreCase(streamImpl)) {
            return chatLangchain4j(systemPrompt, userPrompt);
        }
        return chatJdk(systemPrompt, userPrompt);
    }

    /** 改写 LLM 的 JDK/RestClient 实现（原始，逐字节不变）。 */
    private String chatJdk(String systemPrompt, String userPrompt) {
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

        long llmStart = System.nanoTime();
        JsonNode response = client.post()
                .uri(properties.getApiUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(JsonNode.class);
        long llmMs = (System.nanoTime() - llmStart) / 1_000_000L;
        int totalTokens = response == null ? 0 : response.path("usage").path("total_tokens").asInt(0);
        log.info("[PERF] stage=chat-rewrite-llm model={} ms={} tokens={}", properties.getModel(), llmMs, totalTokens);

        String answer = extractContent(response);
        if (!StringUtils.hasText(answer)) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "Chat LLM未返回有效回答");
        }
        return answer.trim();
    }

    /** 改写 LLM 的 LangChain4j 实现：{@code OpenAiChatModel.chat(ChatRequest)}，content-only。 */
    private String chatLangchain4j(String systemPrompt, String userPrompt) {
        long llmStart = System.nanoTime();
        ChatResponse response = blockingModel().chat(ChatRequest.builder()
                .messages(SystemMessage.from(systemPrompt), UserMessage.from(userPrompt))
                .build());
        long llmMs = (System.nanoTime() - llmStart) / 1_000_000L;
        Integer tokens = response.tokenUsage() == null ? null : response.tokenUsage().totalTokenCount();
        log.info("[PERF] stage=chat-rewrite-llm impl=langchain4j model={} ms={} tokens={}",
                properties.getModel(), llmMs, tokens == null ? 0 : tokens);

        String answer = response.aiMessage() == null ? null : response.aiMessage().text();
        if (!StringUtils.hasText(answer)) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "Chat LLM未返回有效回答");
        }
        return answer.trim();
    }

    private OpenAiChatModel blockingModel() {
        if (lc4jChatModel == null) {
            synchronized (this) {
                if (lc4jChatModel == null) {
                    lc4jChatModel = OpenAiChatModel.builder()
                            .baseUrl(lc4jBaseUrl(properties.getApiUrl()))
                            .apiKey(properties.getApiKey())
                            .modelName(properties.getModel())
                            .temperature(properties.getTemperature())
                            .maxTokens(properties.getMaxTokens())
                            .timeout(Duration.ofSeconds(60))
                            .build();
                }
            }
        }
        return lc4jChatModel;
    }

    private static String lc4jBaseUrl(String apiUrl) {
        return apiUrl.replace("/chat/completions", "").replaceAll("/+$", "");
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
