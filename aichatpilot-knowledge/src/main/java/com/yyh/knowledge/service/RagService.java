package com.yyh.knowledge.service;

import com.yyh.knowledge.dto.KnowledgeAskRequest;
import com.yyh.knowledge.dto.KnowledgeAskResponse;

public interface RagService {
    KnowledgeAskResponse ask(Long kbId, KnowledgeAskRequest request);

    /**
     * 流式问答：检索后通过 {@link RagStreamSink} 逐 token 回吐答案。
     * 与 {@link #ask} 共用检索与 prompt 构造逻辑；流式路径暂不走 FAQ 缓存。
     */
    void askStream(Long kbId, KnowledgeAskRequest request, RagStreamSink sink);
}
