package com.yyh.chat.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyh.chat.config.MediaProperties;
import com.yyh.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AsrService 纯单测：只验证 {@code parseTranscript} 解析与 enabled/参数校验分支，<b>不打网络</b>。
 * 仿 RerankService 的"解析逻辑抽包可见 + mock JSON"做法。
 */
class AsrServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private AsrService newService() {
        return new AsrService(new MediaProperties());
    }

    @Test
    void parseTranscript_extractsTextField() throws Exception {
        AsrService svc = newService();
        assertEquals("你好世界", svc.parseTranscript(mapper.readTree("{\"text\":\"你好世界\"}")));
    }

    @Test
    void parseTranscript_nullOrMissing_returnsNull() throws Exception {
        AsrService svc = newService();
        assertNull(svc.parseTranscript(null));
        assertNull(svc.parseTranscript(mapper.readTree("{\"foo\":\"bar\"}")));
    }

    @Test
    void transcribe_disabledByDefault_throws() {
        AsrService svc = newService(); // MediaProperties.Asr.enabled 默认 false
        BusinessException ex = assertThrows(BusinessException.class,
                () -> svc.transcribe(new byte[]{1, 2, 3}, "a.wav", "audio/wav"));
        assertTrue(ex.getMessage().contains("未启用"));
    }

    @Test
    void transcribe_enabledButEmptyAudio_throws() {
        MediaProperties props = new MediaProperties();
        props.getAsr().setEnabled(true);
        props.getAsr().setApiKey("sk-test");
        AsrService svc = new AsrService(props);
        assertThrows(BusinessException.class, () -> svc.transcribe(new byte[]{}, "a.wav", "audio/wav"));
    }

    @Test
    void transcribe_enabledButUnsupportedType_throws() {
        MediaProperties props = new MediaProperties();
        props.getAsr().setEnabled(true);
        props.getAsr().setApiKey("sk-test");
        AsrService svc = new AsrService(props);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> svc.transcribe(new byte[]{1, 2, 3}, "a.txt", "text/plain"));
        assertTrue(ex.getMessage().contains("不支持的音频类型"));
    }
}
