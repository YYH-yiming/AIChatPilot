package com.yyh.chat.spike;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多模态输入 spike —— 仅 test 作用域，<b>绝不进生产包、不改动任何现有服务代码</b>。
 *
 * <p>目的（对应多模态接入计划 Phase 0，gate）：在接线前，用本项目已有的 Spring {@code RestClient}
 * （不引新依赖、不走 LangChain4j，保真验证原始 HTTP 形态）真打 SiliconFlow，确认两件事：
 * <ul>
 *   <li><b>t1 ASR</b>：{@code POST /v1/audio/transcriptions}（multipart：file + model=SenseVoiceSmall）能通、返回 {@code {text}}。</li>
 *   <li><b>t2 VLM</b>：{@code POST /v1/chat/completions}（messages 含 image_url，model=Qwen2.5-VL）能通、返回非空文本。</li>
 * </ul>
 * 确认 endpoint/鉴权/返回 JSON 形态、模型名是否可用，结论决定生产侧用手写 RestClient（预期）。
 *
 * <p><b>怎么跑</b>（需要 SiliconFlow key，二选一；无 key 自动 {@code assumeTrue} 跳过，不失败）：
 * <pre>
 *   1) 环境变量：$env:MEDIA_SPIKE="1"; $env:LLM_API_KEY="sk-..."
 *      （可选覆盖：MEDIA_ASR_API_URL / MEDIA_ASR_MODEL / MEDIA_VLM_API_URL / MEDIA_VLM_MODEL；
 *        MEDIA_SPIKE_AUDIO=真实音频文件路径，给了就断言转写非空，不给则用内存合成 WAV 只验证 endpoint 形态）
 *   2) 或仓库根放 .env.local（含 LLM_API_KEY 等），仍需 $env:MEDIA_SPIKE="1" 打开本类。
 * 然后（仓库根执行）：
 *   mvn -pl aichatpilot-chat test -Dtest=MediaSpikeTest
 * </pre>
 * 音频/图片均内存合成（WAV 正弦音 + AWT 画字 PNG），无需任何样本文件即可跑通。
 */
@EnabledIfEnvironmentVariable(named = "MEDIA_SPIKE", matches = "1")
class MediaSpikeTest {

    private static final Map<String, String> CFG = loadConfig();
    private static final String API_KEY =
            firstNonBlank(CFG.get("MEDIA_ASR_API_KEY"), CFG.get("LLM_API_KEY"));
    private static final String ASR_URL =
            CFG.getOrDefault("MEDIA_ASR_API_URL", "https://api.siliconflow.cn/v1/audio/transcriptions");
    private static final String ASR_MODEL =
            CFG.getOrDefault("MEDIA_ASR_MODEL", "FunAudioLLM/SenseVoiceSmall");
    private static final String VLM_URL =
            CFG.getOrDefault("MEDIA_VLM_API_URL", "https://api.siliconflow.cn/v1/chat/completions");
    private static final String VLM_MODEL =
            CFG.getOrDefault("MEDIA_VLM_MODEL", "deepseek-ai/DeepSeek-OCR");

    private void requireKey() {
        Assumptions.assumeTrue(API_KEY != null && !API_KEY.isBlank(),
                "未找到 MEDIA_ASR_API_KEY/LLM_API_KEY（设环境变量或仓库根放 .env.local）→ 跳过 spike");
    }

    private RestClient client(String baseUrl, int readTimeoutMs) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(10_000);
        rf.setReadTimeout(readTimeoutMs);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(rf)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
                .build();
    }

    /** 1) ASR：multipart 打 SiliconFlow /v1/audio/transcriptions，确认通 + 返回 {text} 字段。 */
    @Test
    void t1_asrTranscription() throws Exception {
        requireKey();
        byte[] audio;
        String filename;
        String contentType;
        boolean realAudio = false;
        String audioPath = CFG.get("MEDIA_SPIKE_AUDIO");
        if (audioPath != null && !audioPath.isBlank() && Files.exists(Path.of(audioPath))) {
            audio = Files.readAllBytes(Path.of(audioPath));
            filename = Path.of(audioPath).getFileName().toString();
            contentType = guessAudioContentType(filename);
            realAudio = true;
            System.out.println("[t1] 使用真实音频: " + audioPath + " (" + audio.length + " bytes)");
        } else {
            audio = tinyWav();
            filename = "spike.wav";
            contentType = "audio/wav";
            System.out.println("[t1] 使用内存合成 WAV (" + audio.length + " bytes) —— 只验证 endpoint 形态，转写可能为空");
        }

        String boundary = "----AIChatPilotBoundary" + UUID.randomUUID().toString().replace("-", "");
        byte[] bodyBytes = buildMultipartBody(audio, filename, contentType, ASR_MODEL, boundary);

        try {
            // 手工拼 multipart/form-data（显式 boundary + 原始字节）：绕开 FormHttpMessageConverter 在
            // RestClient+SimpleClientHttpRequestFactory 下产不出 file 部件的问题（先前 422 Field required）。
            JsonNode resp = client(ASR_URL, 60_000).post()
                    .contentType(MediaType.parseMediaType("multipart/form-data; boundary=" + boundary))
                    .body(bodyBytes)
                    .retrieve()
                    .body(JsonNode.class);
            System.out.println("[t1] 原始响应: " + resp);
            assertTrue(resp != null && resp.has("text"),
                    "ASR 响应应含 text 字段（确认 endpoint/模型/鉴权可用）");
            String text = resp.path("text").asText("");
            System.out.println("[t1] 转写文本 = \"" + text + "\"  model=" + ASR_MODEL);
            if (realAudio) {
                assertTrue(!text.isBlank(), "真实音频应转写出非空文本");
            }
            System.out.println("[t1] ✅ ASR endpoint 形态确认：字段=text，生产侧手写 RestClient(multipart file+model) 即可。");
        } catch (RestClientResponseException ex) {
            System.out.println("[t1] ❌ HTTP " + ex.getStatusCode() + " 响应体: " + ex.getResponseBodyAsString());
            throw ex;
        }
    }

    /** 2) VLM：messages 含 image_url 打 /v1/chat/completions，确认通 + 返回非空文本（OCR/描述）。 */
    @Test
    void t2_vlmImageUnderstanding() throws Exception {
        requireKey();
        String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(textPng("RAG 9800"));

        Map<String, Object> textPart = Map.of("type", "text", "text", "请OCR这张图片里的文字，并简要描述图片内容。");
        Map<String, Object> imagePart = Map.of("type", "image_url", "image_url", Map.of("url", dataUrl));
        Map<String, Object> userMsg = Map.of("role", "user", "content", List.of(textPart, imagePart));
        Map<String, Object> req = new HashMap<>();
        req.put("model", VLM_MODEL);
        req.put("messages", List.of(userMsg));
        req.put("max_tokens", 512);
        req.put("stream", false);

        try {
            JsonNode resp = client(VLM_URL, 120_000).post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(req)
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode choices = resp == null ? null : resp.path("choices");
            assertTrue(choices != null && choices.isArray() && !choices.isEmpty(),
                    "VLM 响应应含 choices（确认 endpoint/模型/鉴权 + image_url 形态可用）");
            String content = choices.get(0).path("message").path("content").asText("");
            int totalTokens = resp.path("usage").path("total_tokens").asInt(0);
            System.out.println("[t2] VLM 识别文本 = " + content);
            System.out.println("[t2] model=" + VLM_MODEL + " totalTokens=" + totalTokens);
            assertTrue(!content.isBlank(), "VLM 应返回非空文本");
            System.out.println("[t2] ✅ VLM endpoint 形态确认：messages.content=[{type:text},{type:image_url}]，"
                    + "解析 choices[0].message.content（与 ChatLlmService.extractContent 一致）。");
        } catch (RestClientResponseException ex) {
            System.out.println("[t2] ❌ HTTP " + ex.getStatusCode() + " 响应体: " + ex.getResponseBodyAsString());
            throw ex;
        }
    }

    // ---- 手工拼 multipart/form-data 字节体（生产侧 AsrService 同款，提前在 spike 验证） ----
    private static byte[] buildMultipartBody(byte[] audio, String filename, String contentType,
                                             String model, String boundary) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String filePart = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n";
        out.write(filePart.getBytes(StandardCharsets.UTF_8));
        out.write(audio);
        String tail = "\r\n--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"model\"\r\n\r\n"
                + model + "\r\n"
                + "--" + boundary + "--\r\n";
        out.write(tail.getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    // ---- 内存合成测试素材 ----

    /** 生成一段合法的 16-bit PCM 单声道 16kHz 短正弦 WAV（约 0.6s 440Hz），用于验证 ASR endpoint。 */
    private static byte[] tinyWav() {
        int sampleRate = 16_000;
        int seconds = 1;
        int numSamples = sampleRate * seconds;
        int dataSize = numSamples * 2; // 16-bit mono
        ByteBuffer buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(36 + dataSize);
        buf.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        buf.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(16);              // PCM fmt chunk size
        buf.putShort((short) 1);     // audio format = PCM
        buf.putShort((short) 1);     // channels = 1
        buf.putInt(sampleRate);
        buf.putInt(sampleRate * 2);  // byte rate
        buf.putShort((short) 2);     // block align
        buf.putShort((short) 16);    // bits per sample
        buf.put("data".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(dataSize);
        for (int i = 0; i < numSamples; i++) {
            double angle = 2.0 * Math.PI * 440.0 * i / sampleRate;
            buf.putShort((short) (Math.sin(angle) * 12000));
        }
        return buf.array();
    }

    /** 用 AWT 在白底上画一行文字，编码成 PNG，给 VLM 做 OCR/描述。 */
    private static byte[] textPng(String text) throws Exception {
        int w = 360, h = 120;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(Color.BLACK);
        g.setFont(new Font("SansSerif", Font.BOLD, 48));
        g.drawString(text, 30, 75);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static String guessAudioContentType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".webm")) return "audio/webm";
        if (lower.endsWith(".m4a") || lower.endsWith(".mp4")) return "audio/mp4";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        return "application/octet-stream";
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static Map<String, String> loadConfig() {
        Map<String, String> cfg = new HashMap<>();
        for (String k : new String[]{"MEDIA_ASR_API_KEY", "LLM_API_KEY", "MEDIA_ASR_API_URL", "MEDIA_ASR_MODEL",
                "MEDIA_VLM_API_URL", "MEDIA_VLM_MODEL", "MEDIA_SPIKE_AUDIO"}) {
            String v = System.getenv(k);
            if (v != null && !v.isBlank()) {
                cfg.put(k, v);
            }
        }
        for (Path p : new Path[]{Path.of(".env.local"), Path.of("..", ".env.local"), Path.of("../..", ".env.local")}) {
            try {
                if (Files.exists(p)) {
                    for (String line : Files.readAllLines(p)) {
                        String s = line.trim();
                        if (s.isEmpty() || s.startsWith("#") || !s.contains("=")) {
                            continue;
                        }
                        int i = s.indexOf('=');
                        cfg.putIfAbsent(s.substring(0, i).trim(), s.substring(i + 1).trim());
                    }
                    break;
                }
            } catch (Exception ignored) {
                // spike：读不到就跳过，由 requireKey() 决定是否跳过用例
            }
        }
        return cfg;
    }
}
