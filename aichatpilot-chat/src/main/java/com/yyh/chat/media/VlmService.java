package com.yyh.chat.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.yyh.chat.config.MediaProperties;
import com.yyh.common.exception.BusinessException;
import com.yyh.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图像理解（VLM）：把上传图片 OCR + 简述成文本，与用户文字一起当作聊天 {@code content} 复用现有 RAG/Agent 链路
 * （图转文 → 复用 RAG，不做图文联合直答）。对接 SiliconFlow {@code /v1/chat/completions} 多模态
 * （messages.content 含 image_url，model 默认 Qwen2.5-VL）。
 *
 * <p>与 {@link AsrService} 同为手写 {@code RestClient} + 失败显式抛出（用户须知识别失败，否则拿错文本问答）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VlmService {

    private final MediaProperties mediaProperties;

    /**
     * 识别图片并拼出最终问答 content：{@code [用户文字] + 【图片内容】 + VLM识别文本}。
     *
     * @param image       图片字节
     * @param contentType 图片 content-type（image/png 等）
     * @param caption     用户随图输入的文字（可空）
     * @return 拼好的 content（非空），供 sendMessage / 流式链路使用
     */
    public String recognizeToContent(byte[] image, String contentType, String caption) {
        MediaProperties.Vlm vlm = mediaProperties.getVlm();
        if (!vlm.isEnabled()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "图片输入未启用");
        }
        if (!StringUtils.hasText(vlm.getApiKey()) || !StringUtils.hasText(vlm.getApiUrl())) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "缺少 VLM 配置(MEDIA_VLM_API_KEY/MEDIA_VLM_API_URL)");
        }
        if (image == null || image.length == 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "图片内容为空");
        }
        if (image.length > vlm.getMaxBytes()) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "图片过大，超过 " + (vlm.getMaxBytes() / 1024 / 1024) + "MB 上限");
        }
        String baseCt = baseContentType(contentType);
        if (!vlm.getAllowedContentTypes().contains(baseCt)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "不支持的图片类型: " + baseCt);
        }

        long startNs = System.nanoTime();
        String derived;
        try {
            String dataUrl = "data:" + baseCt + ";base64," + Base64.getEncoder().encodeToString(image);
            JsonNode resp = client().post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(buildRequest(vlm, dataUrl))
                    .retrieve()
                    .body(JsonNode.class);
            derived = parseContent(resp);
            long ms = (System.nanoTime() - startNs) / 1_000_000L;
            int totalTokens = resp == null ? 0 : resp.path("usage").path("total_tokens").asInt(0);
            log.info("[PERF] stage=vlm model={} ms={} bytes={} tokens={} chars={}",
                    vlm.getModel(), ms, image.length, totalTokens, derived == null ? 0 : derived.length());
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("VLM 调用失败: {}", ex.getMessage());
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "图片识别失败: " + ex.getMessage());
        }
        if (!StringUtils.hasText(derived)) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "图片识别结果为空，请换一张更清晰的图片");
        }
        return buildContent(caption, derived.trim());
    }

    /** 拼接最终 content：用户文字（若有）+ 图片识别文本。包可见便于单测。 */
    String buildContent(String caption, String derived) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(caption)) {
            sb.append(caption.trim()).append("\n\n");
        }
        sb.append("【图片内容】\n").append(derived);
        return sb.toString();
    }

    /** 解析 /v1/chat/completions 响应的 choices[0].message.content（文本或多模态数组）。包可见便于单测。 */
    String parseContent(JsonNode resp) {
        if (resp == null) {
            return null;
        }
        JsonNode choices = resp.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return null;
        }
        JsonNode contentNode = choices.get(0).path("message").path("content");
        if (contentNode.isTextual()) {
            return contentNode.asText();
        }
        if (contentNode.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : contentNode) {
                if (item.isTextual()) {
                    sb.append(item.asText());
                } else if (item.path("text").isTextual()) {
                    sb.append(item.path("text").asText());
                }
            }
            return sb.toString();
        }
        return null;
    }

    private Map<String, Object> buildRequest(MediaProperties.Vlm vlm, String dataUrl) {
        Map<String, Object> textPart = Map.of("type", "text", "text", vlm.getPrompt());
        Map<String, Object> imagePart = Map.of("type", "image_url", "image_url", Map.of("url", dataUrl));
        Map<String, Object> userMsg = Map.of("role", "user", "content", List.of(textPart, imagePart));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", vlm.getModel());
        request.put("messages", List.of(userMsg));
        request.put("max_tokens", vlm.getMaxTokens());
        request.put("stream", false);
        return request;
    }

    private String baseContentType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return "application/octet-stream";
        }
        return contentType.split(";")[0].trim().toLowerCase();
    }

    private RestClient client() {
        MediaProperties.Vlm vlm = mediaProperties.getVlm();
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(10_000);
        rf.setReadTimeout(Math.max(vlm.getTimeoutSeconds(), 1) * 1000);
        return RestClient.builder()
                .baseUrl(vlm.getApiUrl())
                .requestFactory(rf)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + vlm.getApiKey())
                .build();
    }
}
