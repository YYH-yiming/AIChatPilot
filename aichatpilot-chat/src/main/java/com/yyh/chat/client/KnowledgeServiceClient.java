package com.yyh.chat.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyh.chat.client.dto.KnowledgeAskClientRequest;
import com.yyh.chat.client.dto.KnowledgeAskClientResponse;
import com.yyh.chat.client.dto.KnowledgeBaseClientResponse;
import com.yyh.chat.client.dto.KnowledgeSearchHitClientResponse;
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
import java.util.List;
import java.util.function.Consumer;

@Component
@RequiredArgsConstructor
public class KnowledgeServiceClient {

    private final ChatProperties chatProperties;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final HttpClient streamHttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public KnowledgeAskClientResponse ask(Long kbId, String query, Integer topK) {
        if (kbId == null || kbId <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "knowledge模式下必须提供kbId");
        }

        Result<KnowledgeAskClientResponse> result = buildClient().post()
                .uri(chatProperties.getKnowledge().getServiceUrl() + "/api/knowledge/bases/{id}/ask", kbId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new KnowledgeAskClientRequest(query, topK))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });

        if (result == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "知识库服务返回为空");
        }
        if (result.getCode() != ResultCode.SUCCESS.getCode() || result.getData() == null) {
            String message = StringUtils.hasText(result.getMessage()) ? result.getMessage() : "知识库服务调用失败";
            throw new BusinessException(result.getCode(), message);
        }
        return result.getData();
    }

    public List<KnowledgeBaseClientResponse> listKnowledgeBases() {
        Result<List<KnowledgeBaseClientResponse>> result = buildClient().get()
                .uri(chatProperties.getKnowledge().getServiceUrl() + "/api/knowledge/bases")
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });

        if (result == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "知识库列表返回为空");
        }
        if (result.getCode() != ResultCode.SUCCESS.getCode() || result.getData() == null) {
            String message = StringUtils.hasText(result.getMessage()) ? result.getMessage() : "知识库列表调用失败";
            throw new BusinessException(result.getCode(), message);
        }
        return result.getData();
    }

    public List<KnowledgeSearchHitClientResponse> search(Long kbId, String query, Integer topK) {
        if (kbId == null || kbId <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "knowledge搜索必须提供kbId");
        }

        Result<List<KnowledgeSearchHitClientResponse>> result = buildClient().post()
                .uri(chatProperties.getKnowledge().getServiceUrl() + "/api/knowledge/bases/{id}/search", kbId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new KnowledgeAskClientRequest(query, topK))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });

        if (result == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "知识库检索返回为空");
        }
        if (result.getCode() != ResultCode.SUCCESS.getCode() || result.getData() == null) {
            String message = StringUtils.hasText(result.getMessage()) ? result.getMessage() : "知识库检索调用失败";
            throw new BusinessException(result.getCode(), message);
        }
        return result.getData();
    }

    /**
     * 流式问答：消费 knowledge 的 /ask/stream SSE，逐 token 回调，返回完整答案 + 元信息 + token 数。
     * 用 JDK HttpClient（不引新依赖），透传 X-User-Id/X-Tenant-Id（直连 knowledge，无网关翻译 JWT）。
     */
    public StreamAskResult askStream(Long kbId, String query, Integer topK,
                                     Consumer<KnowledgeAskClientResponse> onMeta,
                                     Consumer<String> onToken) {
        if (kbId == null || kbId <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "knowledge模式下必须提供kbId");
        }
        String url = chatProperties.getKnowledge().getServiceUrl()
                + "/api/knowledge/bases/" + kbId + "/ask/stream";
        String body;
        try {
            body = objectMapper.writeValueAsString(new KnowledgeAskClientRequest(query, topK));
        } catch (Exception ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "构造知识库流式请求失败");
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

        KnowledgeAskClientResponse meta = null;
        StringBuilder fullAnswer = new StringBuilder();
        String doneAnswer = null;
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
                        "知识库流式HTTP " + response.statusCode() + ": " + truncate(err));
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
                            meta = objectMapper.readValue(data, KnowledgeAskClientResponse.class);
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
                            tokenUsed = node.path("tokenUsed").asInt(0);
                            break;
                        } else if ("error".equals(name)) {
                            String msg = objectMapper.readTree(data).path("message").asText("知识库流式失败");
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
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "知识库流式调用被中断");
        } catch (Exception ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "知识库流式调用失败: " + ex.getMessage());
        }

        String answer = StringUtils.hasText(doneAnswer) ? doneAnswer : fullAnswer.toString();
        return new StreamAskResult(answer, meta, tokenUsed);
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 200 ? value : value.substring(0, 200);
    }

    /** 流式问答汇总：完整答案、元信息（references/grounded/kbId/refCount/model）、总 token。 */
    public record StreamAskResult(String answer, KnowledgeAskClientResponse meta, int tokenUsed) {
    }

    private RestClient buildClient() {
        return RestClient.builder()
                .defaultHeader("X-User-Id", String.valueOf(SecurityUtils.currentUserId()))
                .defaultHeader("X-Tenant-Id", String.valueOf(SecurityUtils.currentTenantId()))
                .build();
    }
}
