package com.yyh.knowledge.service;

import com.yyh.knowledge.dto.KnowledgeBaseCreateRequest;
import com.yyh.knowledge.dto.KnowledgeAskRequest;
import com.yyh.knowledge.dto.KnowledgeAskResponse;
import com.yyh.knowledge.dto.KnowledgeSearchHitVO;
import com.yyh.knowledge.dto.KnowledgeSearchRequest;
import com.yyh.knowledge.dto.KnowledgeBaseVO;

import java.util.List;

public interface KnowledgeBaseService {
    KnowledgeBaseVO create(KnowledgeBaseCreateRequest request);
    List<KnowledgeBaseVO> listCurrentTenant();
    KnowledgeBaseVO getById(Long id);
    KnowledgeBaseVO update(Long id, KnowledgeBaseCreateRequest request);
    void delete(Long id);
    List<KnowledgeSearchHitVO> search(Long id, KnowledgeSearchRequest request);
    KnowledgeAskResponse ask(Long id, KnowledgeAskRequest request);

    /** 流式问答：先校验知识库归属（租户隔离），再委托 {@link RagService#askStream}。 */
    void askStream(Long id, KnowledgeAskRequest request, RagStreamSink sink);
}
