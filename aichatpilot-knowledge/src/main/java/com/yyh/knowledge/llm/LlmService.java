package com.yyh.knowledge.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyh.common.exception.BusinessException;
import com.yyh.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
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

    // 思考模式开关：留空=不下发该字段（保持模型默认）；显式 true/false 才下发 enable_thinking。
    @Value("${llm.enable-thinking:}")
    private String enableThinking;

    // 流式实现：jdk=手写 JDK HttpClient（默认）；langchain4j=LC4j OpenAiStreamingChatModel。可瞬时回退。
    @Value("${llm.stream-impl:jdk}")
    private String streamImpl;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient streamHttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // LC4j 流式模型懒构建并缓存（配置在启动期固定）。
    private volatile OpenAiStreamingChatModel lc4jStreamingModel;
    private volatile OpenAiChatModel lc4jChatModel;

    public String chat(String systemPrompt, String userPrompt) {
        validateConfig();
        if (!openAiCompatible) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "当前仅支持OpenAI兼容的LLM接口协议");
        }
        if ("langchain4j".equalsIgnoreCase(streamImpl)) {
            return chatLangchain4j(systemPrompt, userPrompt);
        }
        return chatJdk(systemPrompt, userPrompt);
    }

    /** 阻塞问答 JDK/RestClient 实现（原始，逐字节不变）。 */
    private String chatJdk(String systemPrompt, String userPrompt) {
        RestClient client = RestClient.builder()
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();

        Map<String, Object> request = baseRequestBody(systemPrompt, userPrompt);
        request.put("stream", false);

        long llmStart = System.nanoTime();
        JsonNode response = client.post()
                .uri(apiUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(JsonNode.class);
        long llmMs = (System.nanoTime() - llmStart) / 1_000_000L;
        int totalTokens = response == null ? 0 : response.path("usage").path("total_tokens").asInt(0);
        log.info("[PERF] stage=llm-answer model={} ms={} tokens={}", model, llmMs, totalTokens);

        String answer = extractContent(response);
        if (!StringUtils.hasText(answer)) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "LLM未返回有效回答");
        }
        return answer.trim();
    }

    /** 阻塞问答 LangChain4j 实现：{@code OpenAiChatModel.chat(ChatRequest)}，content-only（与 chatJdk 同语义）；/no_think 保留。 */
    private String chatLangchain4j(String systemPrompt, String userPrompt) {
        boolean thinkingConfigured = StringUtils.hasText(enableThinking);
        boolean thinkingDisabled = thinkingConfigured && !Boolean.parseBoolean(enableThinking.trim());
        String effectiveUser = thinkingDisabled ? userPrompt + "\n\n/no_think" : userPrompt;

        long llmStart = System.nanoTime();
        ChatResponse response = blockingModel().chat(ChatRequest.builder()
                .messages(SystemMessage.from(systemPrompt), UserMessage.from(effectiveUser))
                .build());
        long llmMs = (System.nanoTime() - llmStart) / 1_000_000L;
        Integer tokens = response.tokenUsage() == null ? null : response.tokenUsage().totalTokenCount();
        log.info("[PERF] stage=llm-answer impl=langchain4j model={} ms={} tokens={}", model, llmMs, tokens == null ? 0 : tokens);

        String answer = response.aiMessage() == null ? null : response.aiMessage().text();
        if (!StringUtils.hasText(answer)) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "LLM未返回有效回答");
        }
        return answer.trim();
    }

    /** LC4j 阻塞模型懒构建并缓存（与流式同配置，不开 returnThinking——阻塞只取 content）。 */
    private OpenAiChatModel blockingModel() {
        if (lc4jChatModel == null) {
            synchronized (this) {
                if (lc4jChatModel == null) {
                    lc4jChatModel = OpenAiChatModel.builder()
                            .baseUrl(lc4jBaseUrl())
                            .apiKey(apiKey)
                            .modelName(model)
                            .temperature(temperature)
                            .maxTokens(maxTokens)
                            .timeout(Duration.ofSeconds(60))
                            .build();
                }
            }
        }
        return lc4jChatModel;
    }

    /**
     * 流式问答：逐 delta 回调 {@code onToken}，返回完整答案 + token 数 + 首 token 延迟。与 {@link #chat} 同配置。
     * 按 {@code llm.stream-impl} 派发：jdk={@link #chatStreamJdk}（手写 SSE，默认）/ langchain4j={@link #chatStreamLangchain4j}。
     */
    public StreamResult chatStream(String systemPrompt, String userPrompt, Consumer<String> onToken) {
        validateConfig();
        if (!openAiCompatible) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "当前仅支持OpenAI兼容的LLM接口协议");
        }
        if ("langchain4j".equalsIgnoreCase(streamImpl)) {
            return chatStreamLangchain4j(systemPrompt, userPrompt, onToken);
        }
        return chatStreamJdk(systemPrompt, userPrompt, onToken);
    }

    /** JDK HttpClient 实现（原始实现，逐字节不变）：stream=true 解析 SSE，逐 content delta 回调 onToken。 */
    private StreamResult chatStreamJdk(String systemPrompt, String userPrompt, Consumer<String> onToken) {
        Map<String, Object> payload = baseRequestBody(systemPrompt, userPrompt);
        payload.put("stream", true);
        payload.put("stream_options", Map.of("include_usage", true));

        String body;
        try {
            body = objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "构造LLM流式请求失败");
        }

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(60))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        StringBuilder full = new StringBuilder();
        long start = System.nanoTime();
        long ttftNanos = -1L;
        long reasoningNanos = -1L;
        int reasoningChars = 0;
        int totalTokens = 0;
        try {
            HttpResponse<InputStream> response = streamHttpClient.send(httpRequest,
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                String err;
                try (InputStream es = response.body()) {
                    err = new String(es.readAllBytes(), StandardCharsets.UTF_8);
                }
                throw new BusinessException(ResultCode.INTERNAL_ERROR,
                        "LLM流式接口HTTP " + response.statusCode() + ": " + truncate(err, 200));
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() || !line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring("data:".length()).trim();
                    if ("[DONE]".equals(data)) {
                        break;
                    }
                    JsonNode node = objectMapper.readTree(data);
                    JsonNode usage = node.path("usage");
                    if (usage.path("total_tokens").isNumber()) {
                        totalTokens = usage.path("total_tokens").asInt(totalTokens);
                    }
                    JsonNode choices = node.path("choices");
                    if (choices.isArray() && !choices.isEmpty()) {
                        JsonNode delta = choices.get(0).path("delta");
                        JsonNode reasoning = delta.path("reasoning_content");
                        if (reasoning.isTextual() && !reasoning.asText().isEmpty()) {
                            if (reasoningNanos < 0) {
                                reasoningNanos = System.nanoTime() - start;
                            }
                            reasoningChars += reasoning.asText().length();
                        }
                        JsonNode content = delta.path("content");
                        if (content.isTextual()) {
                            String piece = content.asText();
                            if (!piece.isEmpty()) {
                                if (ttftNanos < 0) {
                                    ttftNanos = System.nanoTime() - start;
                                }
                                full.append(piece);
                                onToken.accept(piece);
                            }
                        }
                    }
                }
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "LLM流式调用被中断");
        } catch (Exception ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "LLM流式调用失败: " + ex.getMessage());
        }

        long ms = (System.nanoTime() - start) / 1_000_000L;
        long ttftMs = ttftNanos < 0 ? -1L : ttftNanos / 1_000_000L;
        long reasoningMs = reasoningNanos < 0 ? -1L : reasoningNanos / 1_000_000L;
        log.info("[PERF] stage=llm-answer-stream model={} reasoningFirstMs={} reasoningChars={} contentTtftMs={} ms={} tokens={}",
                model, reasoningMs, reasoningChars, ttftMs, ms, totalTokens);

        String answer = full.toString();
        if (!StringUtils.hasText(answer)) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "LLM流式未返回有效内容");
        }
        return new StreamResult(answer.trim(), totalTokens, ttftMs);
    }

    /**
     * LangChain4j 实现（P1 迁移）：{@link OpenAiStreamingChatModel} + {@code returnThinking(true)}，回调式逐 token。
     * {@code onPartialThinking} 采集 reasoning（保留思考三段分解），{@code onPartialResponse} 回调 onToken。
     * 与 {@link #chatStreamJdk} 同口径：同款 {@code [PERF]} 日志、同 {@link StreamResult}、保留 {@code /no_think} 思考开关。
     */
    private StreamResult chatStreamLangchain4j(String systemPrompt, String userPrompt, Consumer<String> onToken) {
        boolean thinkingConfigured = StringUtils.hasText(enableThinking);
        boolean thinkingDisabled = thinkingConfigured && !Boolean.parseBoolean(enableThinking.trim());
        String effectiveUser = thinkingDisabled ? userPrompt + "\n\n/no_think" : userPrompt;

        StringBuilder full = new StringBuilder();
        long start = System.nanoTime();
        AtomicLong ttftNanos = new AtomicLong(-1L);
        AtomicLong reasoningNanos = new AtomicLong(-1L);
        AtomicInteger reasoningChars = new AtomicInteger();
        AtomicInteger totalTokens = new AtomicInteger();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        ChatRequest request = ChatRequest.builder()
                .messages(SystemMessage.from(systemPrompt), UserMessage.from(effectiveUser))
                .build();

        streamingModel().chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialThinking(PartialThinking partialThinking) {
                if (reasoningNanos.get() < 0) {
                    reasoningNanos.set(System.nanoTime() - start);
                }
                String t = partialThinking.text();
                if (t != null) {
                    reasoningChars.addAndGet(t.length());
                }
            }

            @Override
            public void onPartialResponse(String piece) {
                if (piece == null || piece.isEmpty()) {
                    return;
                }
                if (ttftNanos.get() < 0) {
                    ttftNanos.set(System.nanoTime() - start);
                }
                full.append(piece);
                onToken.accept(piece);
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                try {
                    if (response != null && response.tokenUsage() != null
                            && response.tokenUsage().totalTokenCount() != null) {
                        totalTokens.set(response.tokenUsage().totalTokenCount());
                    }
                } finally {
                    done.countDown();
                }
            }

            @Override
            public void onError(Throwable t) {
                error.set(t);
                done.countDown();
            }
        });

        boolean completed;
        try {
            completed = done.await(180, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "LLM流式调用被中断");
        }
        if (error.get() != null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR,
                    "LLM流式调用失败(langchain4j): " + error.get().getMessage());
        }
        if (!completed) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "LLM流式调用超时(langchain4j)");
        }

        long ms = (System.nanoTime() - start) / 1_000_000L;
        long ttftMs = ttftNanos.get() < 0 ? -1L : ttftNanos.get() / 1_000_000L;
        long reasoningMs = reasoningNanos.get() < 0 ? -1L : reasoningNanos.get() / 1_000_000L;
        log.info("[PERF] stage=llm-answer-stream impl=langchain4j model={} reasoningFirstMs={} reasoningChars={} contentTtftMs={} ms={} tokens={}",
                model, reasoningMs, reasoningChars.get(), ttftMs, ms, totalTokens.get());

        String answer = full.toString();
        if (!StringUtils.hasText(answer)) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "LLM流式未返回有效内容");
        }
        return new StreamResult(answer.trim(), totalTokens.get(), ttftMs);
    }

    /** LC4j 的 baseUrl 取 /v1 根（自动追加 /chat/completions）；从完整 llm.api-url 去掉路径尾。 */
    private String lc4jBaseUrl() {
        return apiUrl.replace("/chat/completions", "").replaceAll("/+$", "");
    }

    /** LC4j 流式模型懒构建并缓存：配置在启动期固定，无需每请求重建。开 returnThinking 以拿到 reasoning_content。 */
    private OpenAiStreamingChatModel streamingModel() {
        if (lc4jStreamingModel == null) {
            synchronized (this) {
                if (lc4jStreamingModel == null) {
                    lc4jStreamingModel = OpenAiStreamingChatModel.builder()
                            .baseUrl(lc4jBaseUrl())
                            .apiKey(apiKey)
                            .modelName(model)
                            .temperature(temperature)
                            .maxTokens(maxTokens)
                            .returnThinking(true)
                            .timeout(Duration.ofSeconds(120))
                            .build();
                }
            }
        }
        return lc4jStreamingModel;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** 流式生成结果：完整答案、总 token（拿不到为 0）、首 token 延迟毫秒（无内容为 -1）。 */
    public record StreamResult(String fullText, int totalTokens, long ttftMs) {
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

    private Map<String, Object> baseRequestBody(String systemPrompt, String userPrompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        boolean thinkingConfigured = StringUtils.hasText(enableThinking);
        boolean thinkingDisabled = thinkingConfigured && !Boolean.parseBoolean(enableThinking.trim());
        // Qwen3 关思考：①模型级软开关 /no_think 附加到用户消息——由模型 chat 模板解析，与网关是否支持
        // enable_thinking 参数无关，最稳；②同时下发 enable_thinking 字段（部分网关认这个）。两者叠加确保生效。
        String effectiveUser = thinkingDisabled ? userPrompt + "\n\n/no_think" : userPrompt;
        body.put("messages", buildMessages(systemPrompt, effectiveUser));
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        if (thinkingConfigured) {
            body.put("enable_thinking", Boolean.parseBoolean(enableThinking.trim()));
        }
        if (thinkingDisabled) {
            log.info("[THINK] enable_thinking=false + /no_think appended");
        }
        return body;
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
