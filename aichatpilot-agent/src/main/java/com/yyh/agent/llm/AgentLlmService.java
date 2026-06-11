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
@EnableConfigurationProperties(AgentLlmProperties.class)
public class AgentLlmService {

    private final AgentLlmProperties properties;

    // 统一实现开关：jdk=原 RestClient（默认）；langchain4j=LC4j OpenAiChatModel（多 provider 兜底循环不变）。
    @Value("${llm.stream-impl:jdk}")
    private String streamImpl;

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
        if ("langchain4j".equalsIgnoreCase(streamImpl)) {
            return callProviderLangchain4j(provider, systemPrompt, userPrompt);
        }
        return callProviderJdk(provider, systemPrompt, userPrompt);
    }

    /** 单 provider 调用 JDK/RestClient 实现（原始，逐字节不变）。 */
    private AgentLlmResult callProviderJdk(AgentLlmProperties.Provider provider, String systemPrompt, String userPrompt) {
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

        long llmStart = System.nanoTime();
        JsonNode response = client.post()
                .uri(provider.getApiUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(JsonNode.class);
        long llmMs = (System.nanoTime() - llmStart) / 1_000_000L;
        int totalTokens = response == null ? 0 : response.path("usage").path("total_tokens").asInt(0);
        log.info("[PERF] stage=agent-llm provider={} model={} ms={} tokens={}",
                provider.displayName(), provider.getModel(), llmMs, totalTokens);

        String answer = extractContent(response);
        if (!StringUtils.hasText(answer)) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "Agent LLM未返回有效回答");
        }
        return new AgentLlmResult(answer.trim(), response == null ? 0 : response.path("usage").path("total_tokens").asInt(0));
    }

    /** 单 provider 调用 LangChain4j 实现：{@code OpenAiChatModel}（按该 provider 的 base/key/model/超时构建）。 */
    private AgentLlmResult callProviderLangchain4j(AgentLlmProperties.Provider provider, String systemPrompt, String userPrompt) {
        OpenAiChatModel model = OpenAiChatModel.builder()
                .baseUrl(lc4jBaseUrl(provider.getApiUrl()))
                .apiKey(provider.getApiKey())
                .modelName(provider.getModel())
                .temperature(provider.getTemperature())
                .maxTokens(provider.getMaxTokens())
                .timeout(Duration.ofMillis(Math.max(provider.getReadTimeoutMs(), 1000)))
                .build();

        long llmStart = System.nanoTime();
        ChatResponse response = model.chat(ChatRequest.builder()
                .messages(SystemMessage.from(systemPrompt), UserMessage.from(userPrompt))
                .build());
        long llmMs = (System.nanoTime() - llmStart) / 1_000_000L;
        Integer tokensBoxed = response.tokenUsage() == null ? null : response.tokenUsage().totalTokenCount();
        int totalTokens = tokensBoxed == null ? 0 : tokensBoxed;
        log.info("[PERF] stage=agent-llm impl=langchain4j provider={} model={} ms={} tokens={}",
                provider.displayName(), provider.getModel(), llmMs, totalTokens);

        String answer = response.aiMessage() == null ? null : response.aiMessage().text();
        if (!StringUtils.hasText(answer)) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "Agent LLM未返回有效回答");
        }
        return new AgentLlmResult(answer.trim(), totalTokens);
    }

    private static String lc4jBaseUrl(String apiUrl) {
        return apiUrl.replace("/chat/completions", "").replaceAll("/+$", "");
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
