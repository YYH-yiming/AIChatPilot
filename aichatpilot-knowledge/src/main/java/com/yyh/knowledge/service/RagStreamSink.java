package com.yyh.knowledge.service;

import com.yyh.knowledge.dto.KnowledgeAskResponse;

/**
 * RAG 流式回调出口。{@link RagService#askStream} 在检索完成后回调 {@link #meta}（不含 answer），
 * 随后按 LLM delta 逐段回调 {@link #token}，最终回调 {@link #done}（含完整答案与 token 数）。
 * 由控制器实现，把每次回调映射成一个 SSE 事件。
 */
public interface RagStreamSink {

    /** 检索完成、生成开始前触发一次：携带 kbId/query/topK/references/grounded/referenceCount/model（answer 为空）。 */
    void meta(KnowledgeAskResponse meta);

    /** 每个 LLM 输出 delta 触发一次。 */
    void token(String delta);

    /** 生成结束触发一次：fullAnswer 为权威完整答案，tokenUsed 为本次生成总 token（拿不到则 0）。 */
    void done(String fullAnswer, int tokenUsed);
}
