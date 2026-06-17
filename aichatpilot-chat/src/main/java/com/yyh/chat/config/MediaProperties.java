package com.yyh.chat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 多模态输入配置（语音 ASR / 图像 VLM）。前缀 {@code chat.media}，由 {@code @ConfigurationPropertiesScan} 自动注册。
 *
 * <p>设计：每个模态独立 {@code enabled} 开关（默认 false），出问题可瞬时回退、互不影响；
 * key/url/model 独立于 chat LLM（chat LLM 可能指向 ark 火山，而 ASR/VLM 走 SiliconFlow）。
 */
@Data
@ConfigurationProperties(prefix = "chat.media")
public class MediaProperties {

    private final Asr asr = new Asr();
    private final Vlm vlm = new Vlm();

    /** 语音转写（SiliconFlow /v1/audio/transcriptions，OpenAI Whisper 兼容）。 */
    @Data
    public static class Asr {
        private boolean enabled = true;
        private String apiUrl = "https://api.siliconflow.cn/v1/audio/transcriptions";
        private String apiKey;
        private String model = "FunAudioLLM/SenseVoiceSmall";
        private int timeoutSeconds = 30;
        private long maxBytes = 26_214_400L; // 25MB
        private List<String> allowedContentTypes = List.of(
                "audio/webm", "audio/mpeg", "audio/mp3", "audio/wav", "audio/x-wav",
                "audio/mp4", "audio/x-m4a", "audio/m4a", "audio/ogg");
    }

    /** 图像理解（SiliconFlow /v1/chat/completions 多模态，image_url）。Phase 2 使用。 */
    @Data
    public static class Vlm {
        private boolean enabled = true;
        private String apiUrl = "https://api.siliconflow.cn/v1/chat/completions";
        private String apiKey;
        private String model = "deepseek-ai/DeepSeek-OCR";
        private int timeoutSeconds = 120;
        private int maxTokens = 1024;
        private long maxBytes = 10_485_760L; // 10MB
        private String prompt = "请识别这张图片中的所有文字(OCR)，并简要描述图片内容，输出纯文本，供后续检索与回答使用。";
        private List<String> allowedContentTypes = List.of(
                "image/png", "image/jpeg", "image/jpg", "image/webp", "image/gif", "image/bmp");
    }
}
