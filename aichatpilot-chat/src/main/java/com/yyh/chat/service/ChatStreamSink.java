package com.yyh.chat.service;

import java.util.Map;

/**
 * Chat 流式出口：把 knowledge 的流式回调映射成 chat → 客户端的 SSE 事件。
 * 由 {@code ChatStreamService} 实现（写 SseEmitter）；{@code start}/{@code done} 由 ChatStreamService 统一发。
 */
public interface ChatStreamSink {

    /** 检索元信息（kbId/grounded/referenceCount/references/rewrittenQuery），映射成 `meta` 事件。 */
    void meta(Map<String, Object> meta);

    /** 一个答案 delta，映射成 `token` 事件。 */
    void token(String text);
}
