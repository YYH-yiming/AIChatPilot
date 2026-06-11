package com.yyh.chat.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyh.chat.client.dto.AgentChatClientRequest;
import com.yyh.chat.client.dto.AgentChatClientResponse;
import com.yyh.chat.config.ChatProperties;
import com.yyh.chat.support.SecurityUtils;
import com.yyh.common.exception.BusinessException;
import com.yyh.common.result.Result;
import com.yyh.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

@Component
@RequiredArgsConstructor
public class AgentServiceClient {

    private final ChatProperties chatProperties;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final HttpClient streamHttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    public AgentChatClientResponse chat(AgentChatClientRequest request) {
        Result<AgentChatClientResponse> result = buildClient().post()
                .uri(chatProperties.getAgent().getServiceUrl() + "/api/agent/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });

        if (result == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "Agent服务返回为空");
        }
        if (result.getCode() != ResultCode.SUCCESS.getCode() || result.getData() == null) {
            String message = StringUtils.hasText(result.getMessage()) ? result.getMessage() : "Agent服务调用失败";
            throw new BusinessException(result.getCode(), message);
        }
        return result.getData();
    }

    /**
     * 流式对话：消费 agent 的 /chat/stream SSE，逐 token 回调，返回完整答案 + 意图 + kbId + token 数 + 最后的 meta（含 references）。
     * 用 JDK HttpClient（不引新依赖），透传 X-User-Id/X-Tenant-Id。镜像 KnowledgeServiceClient.askStream。
     */
    public StreamChatResult chatStream(AgentChatClientRequest request,
                                       Consumer<Map<String, Object>> onMeta,
                                       Consumer<String> onToken) {
        String url = chatProperties.getAgent().getServiceUrl() + "/api/agent/chat/stream";
        String body;
        try {
            body = objectMapper.writeValueAsString(request);
        } catch (Exception ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "构造Agent流式请求失败");
        }
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(180))
                .header("X-User-Id", String.valueOf(SecurityUtils.currentUserId()))
                .header("X-Tenant-Id", String.valueOf(SecurityUtils.currentTenantId()))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        Map<String, Object> meta = null;
        StringBuilder fullAnswer = new StringBuilder();
        String doneAnswer = null;
        String intent = null;
        Long kbId = null;
        int tokenUsed = 0;
        try {
            HttpResponse<InputStream> response = streamHttpClient.send(httpRequest,
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                String err;
                try (InputStream es = response.body()) {
                    err = new String(es.readAllBytes(), StandardCharsets.UTF_8);
                }
                throw new BusinessException(ResultCode.INTERNAL_ERROR,
                        "Agent流式HTTP " + response.statusCode() + ": " + truncate(err));
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String event = "message";
                StringBuilder dataBuf = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (dataBuf.length() == 0) {
                            continue;
                        }
                        String name = event;
                        String data = dataBuf.toString();
                        event = "message";
                        dataBuf.setLength(0);
                        if ("meta".equals(name)) {
                            meta = objectMapper.readValue(data, MAP_TYPE);
                            if (onMeta != null) {
                                onMeta.accept(meta);
                            }
                        } else if ("token".equals(name)) {
                            String text = objectMapper.readTree(data).path("text").asText("");
                            if (!text.isEmpty()) {
                                fullAnswer.append(text);
                                if (onToken != null) {
                                    onToken.accept(text);
                                }
                            }
                        } else if ("done".equals(name)) {
                            JsonNode node = objectMapper.readTree(data);
                            doneAnswer = node.path("answer").asText(null);
                            intent = node.path("intent").asText(null);
                            kbId = node.path("kbId").isNumber() ? node.path("kbId").asLong() : null;
                            tokenUsed = node.path("tokenUsed").asInt(0);
                            break;
                        } else if ("error".equals(name)) {
                            String msg = objectMapper.readTree(data).path("message").asText("Agent流式失败");
                            throw new BusinessException(ResultCode.INTERNAL_ERROR, msg);
                        }
                    } else if (line.startsWith("event:")) {
                        event = line.substring("event:".length()).trim();
                    } else if (line.startsWith("data:")) {
                        if (dataBuf.length() > 0) {
                            dataBuf.append("\n");
                        }
                        dataBuf.append(line.substring("data:".length()).trim());
                    }
                }
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "Agent流式调用被中断");
        } catch (Exception ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "Agent流式调用失败: " + ex.getMessage());
        }

        String answer = StringUtils.hasText(doneAnswer) ? doneAnswer : fullAnswer.toString();
        return new StreamChatResult(answer, intent, kbId, tokenUsed, meta);
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 200 ? value : value.substring(0, 200);
    }

    /** Agent 流式汇总：完整答案、意图、kbId、总 token、最后的 meta（含 references）。 */
    public record StreamChatResult(String answer, String intent, Long kbId, int tokenUsed, Map<String, Object> meta) {
    }

    private RestClient buildClient() {
        return RestClient.builder()
                .defaultHeader("X-User-Id", String.valueOf(SecurityUtils.currentUserId()))
                .defaultHeader("X-Tenant-Id", String.valueOf(SecurityUtils.currentTenantId()))
                .build();
    }
}
