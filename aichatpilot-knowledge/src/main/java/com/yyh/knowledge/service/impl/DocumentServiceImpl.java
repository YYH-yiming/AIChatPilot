package com.yyh.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yyh.common.exception.BusinessException;
import com.yyh.common.result.ResultCode;
import com.yyh.knowledge.dto.ChunkVO;
import com.yyh.knowledge.dto.DocumentUploadResponse;
import com.yyh.knowledge.entity.KnowledgeBase;
import com.yyh.knowledge.entity.KnowledgeChunk;
import com.yyh.knowledge.entity.KnowledgeDocument;
import com.yyh.knowledge.mapper.KnowledgeBaseMapper;
import com.yyh.knowledge.mapper.KnowledgeChunkMapper;
import com.yyh.knowledge.mapper.KnowledgeDocumentMapper;
import com.yyh.knowledge.minio.MinioService;
import com.yyh.knowledge.search.RetrievalService;
import com.yyh.knowledge.service.ChunkService;
import com.yyh.knowledge.service.DocumentParseService;
import com.yyh.knowledge.service.DocumentService;
import com.yyh.knowledge.support.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final MinioService minioService;
    private final DocumentParseService documentParseService;
    private final ChunkService chunkService;
    private final RetrievalService retrievalService;

    @Override
    @Transactional
    public DocumentUploadResponse uploadDocument(Long kbId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "上传文件不能为空");
        }

        KnowledgeBase knowledgeBase = requireKnowledgeBase(kbId);
        KnowledgeDocument document = new KnowledgeDocument();
        document.setKbId(kbId);
        document.setFileName(file.getOriginalFilename());
        document.setFileSize(file.getSize());
        document.setFileType(fileType(file.getOriginalFilename()));
        document.setParseStatus(1);
        document.setChunkCount(0);

        String fileUrl = null;
        try {
            fileUrl = minioService.upload(kbId, file.getOriginalFilename(), file.getInputStream(), file.getSize(), file.getContentType());
            document.setFileUrl(fileUrl);
            knowledgeDocumentMapper.insert(document);

            String parsedText = documentParseService.parse(file);
            List<KnowledgeChunk> chunks = chunkService.splitAndSave(kbId, document.getId(), parsedText);
            retrievalService.indexChunks(kbId, chunks);

            document.setParseStatus(2);
            document.setChunkCount(chunks.size());
            knowledgeDocumentMapper.updateById(document);

            knowledgeBase.setDocCount(defaultZero(knowledgeBase.getDocCount()) + 1);
            knowledgeBase.setChunkCount(defaultZero(knowledgeBase.getChunkCount()) + chunks.size());
            knowledgeBaseMapper.updateById(knowledgeBase);
            return toDocumentResponse(document);
        } catch (Exception ex) {
            if (document.getId() != null) {
                document.setParseStatus(3);
                document.setErrorMsg(ex.getMessage());
                knowledgeDocumentMapper.updateById(document);
            }
            minioService.deleteByUrl(fileUrl);
            throw ex instanceof BusinessException businessException
                    ? businessException
                    : new BusinessException(ResultCode.INTERNAL_ERROR, "文档上传处理失败: " + ex.getMessage());
        }
    }

    @Override
    public List<DocumentUploadResponse> listDocuments(Long kbId) {
        requireKnowledgeBase(kbId);
        return knowledgeDocumentMapper.selectList(
                        new LambdaQueryWrapper<KnowledgeDocument>()
                                .eq(KnowledgeDocument::getKbId, kbId)
                                .orderByDesc(KnowledgeDocument::getId)
                ).stream()
                .map(this::toDocumentResponse)
                .toList();
    }

    @Override
    public List<ChunkVO> listChunks(Long kbId, Long docId) {
        requireKnowledgeBase(kbId);
        KnowledgeDocument document = knowledgeDocumentMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeDocument>()
                        .eq(KnowledgeDocument::getId, docId)
                        .eq(KnowledgeDocument::getKbId, kbId)
        );
        if (document == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "文档不存在");
        }
        return knowledgeChunkMapper.selectList(
                        new LambdaQueryWrapper<KnowledgeChunk>()
                                .eq(KnowledgeChunk::getDocId, docId)
                                .orderByAsc(KnowledgeChunk::getChunkIndex)
                ).stream()
                .map(this::toChunkVO)
                .toList();
    }

    private KnowledgeBase requireKnowledgeBase(Long kbId) {
        Long tenantId = SecurityUtils.currentTenantId();
        KnowledgeBase knowledgeBase = knowledgeBaseMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeBase>()
                        .eq(KnowledgeBase::getId, kbId)
                        .eq(KnowledgeBase::getTenantId, tenantId)
        );
        if (knowledgeBase == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "知识库不存在");
        }
        return knowledgeBase;
    }

    private DocumentUploadResponse toDocumentResponse(KnowledgeDocument document) {
        DocumentUploadResponse response = new DocumentUploadResponse();
        BeanUtils.copyProperties(document, response);
        response.setDocId(document.getId());
        return response;
    }

    private ChunkVO toChunkVO(KnowledgeChunk chunk) {
        ChunkVO vo = new ChunkVO();
        BeanUtils.copyProperties(chunk, vo);
        return vo;
    }

    private String fileType(String originalFilename) {
        if (originalFilename == null || !originalFilename.contains(".")) {
            return "unknown";
        }
        return originalFilename.substring(originalFilename.lastIndexOf('.') + 1).toLowerCase();
    }

    private int defaultZero(Integer value) {
        return value == null ? 0 : value;
    }
}
