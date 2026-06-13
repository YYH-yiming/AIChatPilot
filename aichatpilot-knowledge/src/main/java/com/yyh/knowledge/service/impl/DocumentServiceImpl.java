package com.yyh.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yyh.common.exception.BusinessException;
import com.yyh.common.result.ResultCode;
import com.yyh.knowledge.config.KnowledgeReliabilityProperties;
import com.yyh.knowledge.dto.ChunkVO;
import com.yyh.knowledge.dto.DocumentUploadResponse;
import com.yyh.knowledge.entity.KnowledgeBase;
import com.yyh.knowledge.entity.KnowledgeChunk;
import com.yyh.knowledge.entity.KnowledgeDocument;
import com.yyh.knowledge.kafka.DocumentEventProducer;
import com.yyh.knowledge.lock.DistributedLockService;
import com.yyh.knowledge.mapper.KnowledgeBaseMapper;
import com.yyh.knowledge.mapper.KnowledgeChunkMapper;
import com.yyh.knowledge.mapper.KnowledgeDocumentMapper;
import com.yyh.knowledge.minio.MinioService;
import com.yyh.knowledge.service.DocumentService;
import com.yyh.knowledge.support.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final MinioService minioService;
    private final DocumentAsyncProcessService documentAsyncProcessService;
    private final ObjectProvider<DocumentEventProducer> documentEventProducerProvider;
    private final DistributedLockService distributedLockService;
    private final KnowledgeReliabilityProperties reliabilityProperties;

    @Value("${knowledge.async.enabled:true}")
    private boolean asyncEnabled;

    @Override
    @Transactional
    public DocumentUploadResponse uploadDocument(Long kbId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "上传文件不能为空");
        }

        String lockKey = buildUploadLockKey(kbId, file);
        DistributedLockService.LockHandle lockHandle = distributedLockService.tryLock(
                "submit",
                lockKey,
                Duration.ofSeconds(Math.max(reliabilityProperties.getUploadLock().getUploadLeaseSeconds(), 5))
        );
        if (!lockHandle.isAcquired()) {
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, "相同文档正在上传或处理中，请勿重复提交");
        }
        try {
            KnowledgeBase knowledgeBase = requireKnowledgeBase(kbId);
            KnowledgeDocument document = new KnowledgeDocument();
            document.setKbId(kbId);
            document.setFileName(file.getOriginalFilename());
            document.setFileSize(file.getSize());
            document.setFileType(fileType(file.getOriginalFilename()));
            document.setParseStatus(asyncEnabled ? 0 : 1);
            document.setChunkCount(0);

            String fileUrl = null;
            try {
                fileUrl = minioService.upload(kbId, file.getOriginalFilename(), file.getInputStream(), file.getSize(), file.getContentType());
                document.setFileUrl(fileUrl);
                knowledgeDocumentMapper.insert(document);
                knowledgeBase.setDocCount(defaultZero(knowledgeBase.getDocCount()) + 1);
                knowledgeBaseMapper.updateById(knowledgeBase);
                if (asyncEnabled) {
                    publishDocumentEventAfterCommit(document.getId(), kbId);
                } else {
                    documentAsyncProcessService.processUploadedDocument(kbId, document.getId());
                    document = knowledgeDocumentMapper.selectById(document.getId());
                }
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
        } finally {
            distributedLockService.unlock(lockHandle);
        }
    }

    @Override
    @Transactional
    public DocumentUploadResponse reprocessDocument(Long kbId, Long docId) {
        requireKnowledgeBase(kbId);
        KnowledgeDocument document = requireDocument(kbId, docId);
        // 重处理：重置状态避免 processUploadedDocument 因 parseStatus==2 跳过；原文件仍在 MinIO，
        // processUploadedDocument 会重新解析→切分(按当前 flag/策略)→重灌索引，docId 不变（eval gold 不漂移）。
        document.setParseStatus(asyncEnabled ? 0 : 1);
        document.setErrorMsg(null);
        knowledgeDocumentMapper.updateById(document);
        if (asyncEnabled) {
            publishDocumentEventAfterCommit(docId, kbId);
        } else {
            documentAsyncProcessService.processUploadedDocument(kbId, docId);
            document = knowledgeDocumentMapper.selectById(docId);
        }
        return toDocumentResponse(document);
    }

    @Override
    public DocumentUploadResponse getDocument(Long kbId, Long docId) {
        requireKnowledgeBase(kbId);
        return toDocumentResponse(requireDocument(kbId, docId));
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
        requireDocument(kbId, docId);
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
        response.setMessage(statusMessage(document));
        return response;
    }

    private KnowledgeDocument requireDocument(Long kbId, Long docId) {
        KnowledgeDocument document = knowledgeDocumentMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeDocument>()
                        .eq(KnowledgeDocument::getId, docId)
                        .eq(KnowledgeDocument::getKbId, kbId)
        );
        if (document == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "文档不存在");
        }
        return document;
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

    private String buildUploadLockKey(Long kbId, MultipartFile file) {
        Long tenantId = SecurityUtils.currentTenantId();
        String fileName = file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename();
        return tenantId + ":" + kbId + ":" + fileName + ":" + file.getSize();
    }

    private void publishDocumentEventAfterCommit(Long docId, Long kbId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            sendDocumentEvent(docId, kbId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    sendDocumentEvent(docId, kbId);
                } catch (Exception ex) {
                    KnowledgeDocument failedDocument = new KnowledgeDocument();
                    failedDocument.setId(docId);
                    failedDocument.setParseStatus(3);
                    failedDocument.setErrorMsg(ex.getMessage());
                    knowledgeDocumentMapper.updateById(failedDocument);
                }
            }
        });
    }

    private void sendDocumentEvent(Long docId, Long kbId) {
        DocumentEventProducer documentEventProducer = documentEventProducerProvider.getIfAvailable();
        if (documentEventProducer == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "未找到Kafka文档事件生产者");
        }
        documentEventProducer.sendDocumentUploadEvent(docId, kbId);
    }

    private String statusMessage(KnowledgeDocument document) {
        if (document.getParseStatus() == null) {
            return null;
        }
        return switch (document.getParseStatus()) {
            case 0 -> "上传成功，正在排队处理";
            case 1 -> "文档正在处理中";
            case 2 -> "解析完成";
            case 3 -> document.getErrorMsg() == null ? "文档处理失败" : document.getErrorMsg();
            default -> null;
        };
    }
}
