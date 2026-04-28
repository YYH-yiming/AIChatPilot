package com.yyh.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yyh.common.exception.BusinessException;
import com.yyh.common.result.ResultCode;
import com.yyh.knowledge.dto.KnowledgeAskRequest;
import com.yyh.knowledge.dto.KnowledgeAskResponse;
import com.yyh.knowledge.dto.KnowledgeBaseCreateRequest;
import com.yyh.knowledge.dto.KnowledgeSearchHitVO;
import com.yyh.knowledge.dto.KnowledgeSearchRequest;
import com.yyh.knowledge.dto.KnowledgeBaseVO;
import com.yyh.knowledge.entity.KnowledgeBase;
import com.yyh.knowledge.entity.KnowledgeDocument;
import com.yyh.knowledge.mapper.KnowledgeBaseMapper;
import com.yyh.knowledge.mapper.KnowledgeChunkMapper;
import com.yyh.knowledge.mapper.KnowledgeDocumentMapper;
import com.yyh.knowledge.minio.MinioService;
import com.yyh.knowledge.search.RetrievalService;
import com.yyh.knowledge.service.KnowledgeBaseService;
import com.yyh.knowledge.service.RagService;
import com.yyh.knowledge.support.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final MinioService minioService;
    private final RetrievalService retrievalService;
    private final RagService ragService;

    @Override
    @Transactional
    public KnowledgeBaseVO create(KnowledgeBaseCreateRequest request) {
        Long tenantId = SecurityUtils.currentTenantId();
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setTenantId(tenantId);
        knowledgeBase.setName(request.getName());
        knowledgeBase.setDescription(request.getDescription());
        knowledgeBase.setEmbeddingModel(request.getEmbeddingModel());
        knowledgeBase.setDocCount(0);
        knowledgeBase.setChunkCount(0);
        knowledgeBase.setStatus(1);
        knowledgeBaseMapper.insert(knowledgeBase);
        return toVO(knowledgeBase);
    }

    @Override
    public List<KnowledgeBaseVO> listCurrentTenant() {
        Long tenantId = SecurityUtils.currentTenantId();
        return knowledgeBaseMapper.selectList(
                        new LambdaQueryWrapper<KnowledgeBase>()
                                .eq(KnowledgeBase::getTenantId, tenantId)
                                .orderByDesc(KnowledgeBase::getId)
                ).stream()
                .map(this::toVO)
                .toList();
    }

    @Override
    public KnowledgeBaseVO getById(Long id) {
        return toVO(requireKnowledgeBase(id));
    }

    @Override
    @Transactional
    public KnowledgeBaseVO update(Long id, KnowledgeBaseCreateRequest request) {
        KnowledgeBase knowledgeBase = requireKnowledgeBase(id);
        knowledgeBase.setName(request.getName());
        knowledgeBase.setDescription(request.getDescription());
        knowledgeBase.setEmbeddingModel(request.getEmbeddingModel());
        knowledgeBaseMapper.updateById(knowledgeBase);
        return toVO(knowledgeBase);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        KnowledgeBase knowledgeBase = requireKnowledgeBase(id);
        List<KnowledgeDocument> documents = knowledgeDocumentMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocument>().eq(KnowledgeDocument::getKbId, id)
        );
        documents.forEach(document -> minioService.deleteByUrl(document.getFileUrl()));
        knowledgeChunkMapper.delete(new LambdaQueryWrapper<com.yyh.knowledge.entity.KnowledgeChunk>().eq(com.yyh.knowledge.entity.KnowledgeChunk::getKbId, id));
        knowledgeDocumentMapper.delete(new LambdaQueryWrapper<KnowledgeDocument>().eq(KnowledgeDocument::getKbId, id));
        knowledgeBaseMapper.deleteById(knowledgeBase.getId());
        retrievalService.deleteKnowledgeBaseIndexes(id);
    }

    @Override
    public List<KnowledgeSearchHitVO> search(Long id, KnowledgeSearchRequest request) {
        requireKnowledgeBase(id);
        return retrievalService.search(request.getQuery(), id, request.getTopK());
    }

    @Override
    public KnowledgeAskResponse ask(Long id, KnowledgeAskRequest request) {
        requireKnowledgeBase(id);
        return ragService.ask(id, request);
    }

    private KnowledgeBase requireKnowledgeBase(Long id) {
        Long tenantId = SecurityUtils.currentTenantId();
        KnowledgeBase knowledgeBase = knowledgeBaseMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeBase>()
                        .eq(KnowledgeBase::getId, id)
                        .eq(KnowledgeBase::getTenantId, tenantId)
        );
        if (knowledgeBase == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "知识库不存在");
        }
        return knowledgeBase;
    }

    private KnowledgeBaseVO toVO(KnowledgeBase knowledgeBase) {
        KnowledgeBaseVO vo = new KnowledgeBaseVO();
        BeanUtils.copyProperties(knowledgeBase, vo);
        return vo;
    }
}
