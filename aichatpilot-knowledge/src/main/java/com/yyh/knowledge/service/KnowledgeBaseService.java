package com.yyh.knowledge.service;

import com.yyh.knowledge.dto.KnowledgeBaseCreateRequest;
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
}
