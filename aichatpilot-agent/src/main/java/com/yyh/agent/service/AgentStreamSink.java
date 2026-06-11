package com.yyh.agent.service;

import java.util.Map;

/**
 * Agent 流式出口：把答案 token / 检索元信息映射成 agent → chat 的 SSE 事件。
 * 由 AgentController 的 /chat/stream 实现（写 SseEmitter）；start/done 由控制器统一发。
 */
public interface AgentStreamSink {

    /** 检索元信息（intent/kbId/grounded/referenceCount/references…），映射成 `meta` 事件。 */
    void meta(Map<String, Object> meta);

    /** 一个答案 delta，映射成 `token` 事件。 */
    void token(String text);
}
