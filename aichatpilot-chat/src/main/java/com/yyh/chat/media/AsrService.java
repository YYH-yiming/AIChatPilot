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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 语音转写（ASR）：把上传的音频转成文本，供聊天入口当作 {@code content} 复用现有 RAG/Agent 链路。
 * 对接 SiliconFlow 的 {@code /v1/audio/transcriptions}（OpenAI Whisper 兼容，model 默认 SenseVoiceSmall）。
 *
 * <p>容错风格：与 {@code RerankService} 同为手写 {@code RestClient}，但 ASR 失败<b>不静默降级</b>——
 * 转写失败用户必须知道（否则会拿错的文本去问答），故显式抛 {@link BusinessException}，由全局异常处理出干净 Result。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsrService {

    private final MediaProperties mediaProperties;

    /**
     * 转写音频为文本。
     *
     * @param audio       音频字节
     * @param filename    原始文件名（multipart filename，影响部分服务的格式判断）
     * @param contentType 浏览器上送的 content-type（可能带 codecs 后缀）
     * @return 非空转写文本
     */
    public String transcribe(byte[] audio, String filename, String contentType) {
        MediaProperties.Asr asr = mediaProperties.getAsr();
        if (!asr.isEnabled()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "语音输入未启用");
        }
        if (!StringUtils.hasText(asr.getApiKey()) || !StringUtils.hasText(asr.getApiUrl())) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "缺少 ASR 配置(MEDIA_ASR_API_KEY/MEDIA_ASR_API_URL)");
        }
        if (audio == null || audio.length == 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "音频内容为空");
        }
        if (audio.length > asr.getMaxBytes()) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "音频过大，超过 " + (asr.getMaxBytes() / 1024 / 1024) + "MB 上限");
        }
        String baseCt = baseContentType(contentType);
        if (!asr.getAllowedContentTypes().contains(baseCt)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "不支持的音频类型: " + baseCt);
        }

        long start = System.nanoTime();
        try {
            // 手工拼 multipart/form-data（显式 boundary + 原始字节）：RestClient+SimpleClientHttpRequestFactory 下
            // FormHttpMessageConverter 产不出合格 file 部件（spike 实测两种 MultiValueMap 写法都 422 Field required）。
            String boundary = "----AIChatPilotBoundary" + UUID.randomUUID().toString().replace("-", "");
            JsonNode resp = client().post()
                    .contentType(MediaType.parseMediaType("multipart/form-data; boundary=" + boundary))
                    .body(buildMultipartBody(audio, filename, baseCt, asr.getModel(), boundary))
                    .retrieve()
                    .body(JsonNode.class);
            String text = parseTranscript(resp);
            long ms = (System.nanoTime() - start) / 1_000_000L;
            log.info("[PERF] stage=asr model={} ms={} bytes={} chars={}",
                    asr.getModel(), ms, audio.length, text == null ? 0 : text.length());
            if (!StringUtils.hasText(text)) {
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "语音识别结果为空，请重试或换用更清晰的录音");
            }
            return text.trim();
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("ASR 调用失败: {}", ex.getMessage());
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "语音识别失败: " + ex.getMessage());
        }
    }

    /** 解析 SiliconFlow /v1/audio/transcriptions 响应（OpenAI 风格 {@code {"text": "..."}}）。包可见便于单测。 */
    String parseTranscript(JsonNode resp) {
        if (resp == null) {
            return null;
        }
        return resp.path("text").asText(null);
    }

    /** 取主类型（去掉 ;codecs=... 等后缀），统一小写；空则视为通用二进制。 */
    private String baseContentType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return "application/octet-stream";
        }
        return contentType.split(";")[0].trim().toLowerCase();
    }

    private byte[] buildMultipartBody(byte[] audio, String filename, String contentType,
                                      String model, String boundary) {
        String safeName = StringUtils.hasText(filename) ? filename : "audio";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String filePart = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + safeName + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n";
        out.writeBytes(filePart.getBytes(StandardCharsets.UTF_8));
        out.writeBytes(audio);
        String tail = "\r\n--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"model\"\r\n\r\n"
                + model + "\r\n"
                + "--" + boundary + "--\r\n";
        out.writeBytes(tail.getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private RestClient client() {
        MediaProperties.Asr asr = mediaProperties.getAsr();
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(10_000);
        rf.setReadTimeout(Math.max(asr.getTimeoutSeconds(), 1) * 1000);
        return RestClient.builder()
                .baseUrl(asr.getApiUrl())
                .requestFactory(rf)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + asr.getApiKey())
                .build();
    }
}
