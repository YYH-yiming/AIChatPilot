package com.yyh.chat.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyh.chat.config.MediaProperties;
import com.yyh.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** VlmService 纯单测：parseContent（文本/数组）/ buildContent / 校验分支，<b>不打网络</b>。 */
class VlmServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private VlmService newService() {
        return new VlmService(new MediaProperties());
    }

    @Test
    void parseContent_textContent() throws Exception {
        VlmService svc = newService();
        String json = "{\"choices\":[{\"message\":{\"content\":\"图中文字：RAG 9800\"}}]}";
        assertEquals("图中文字：RAG 9800", svc.parseContent(mapper.readTree(json)));
    }

    @Test
    void parseContent_arrayContent() throws Exception {
        VlmService svc = newService();
        String json = "{\"choices\":[{\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"AB\"},"
                + "{\"type\":\"text\",\"text\":\"CD\"}]}}]}";
        assertEquals("ABCD", svc.parseContent(mapper.readTree(json)));
    }

    @Test
    void parseContent_nullOrEmptyChoices_returnsNull() throws Exception {
        VlmService svc = newService();
        assertNull(svc.parseContent(null));
        assertNull(svc.parseContent(mapper.readTree("{\"choices\":[]}")));
    }

    @Test
    void buildContent_withAndWithoutCaption() {
        VlmService svc = newService();
        assertEquals("【图片内容】\n描述", svc.buildContent(null, "描述"));
        assertEquals("这是什么\n\n【图片内容】\n描述", svc.buildContent("这是什么", "描述"));
    }

    @Test
    void recognize_disabledByDefault_throws() {
        VlmService svc = newService(); // MediaProperties.Vlm.enabled 默认 false
        BusinessException ex = assertThrows(BusinessException.class,
                () -> svc.recognizeToContent(new byte[]{1, 2, 3}, "image/png", null));
        assertTrue(ex.getMessage().contains("未启用"));
    }

    @Test
    void recognize_enabledButUnsupportedType_throws() {
        MediaProperties props = new MediaProperties();
        props.getVlm().setEnabled(true);
        props.getVlm().setApiKey("sk-test");
        VlmService svc = new VlmService(props);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> svc.recognizeToContent(new byte[]{1, 2, 3}, "application/pdf", null));
        assertTrue(ex.getMessage().contains("不支持的图片类型"));
    }
}
