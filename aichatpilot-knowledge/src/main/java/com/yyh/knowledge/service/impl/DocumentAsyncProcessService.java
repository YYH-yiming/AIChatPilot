package com.yyh.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yyh.knowledge.cache.FaqCacheService;
import com.yyh.knowledge.config.KnowledgeReliabilityProperties;
import com.yyh.knowledge.entity.KnowledgeBase;
import com.yyh.knowledge.entity.KnowledgeChunk;
import com.yyh.knowledge.entity.KnowledgeDocument;
import com.yyh.knowledge.lock.DistributedLockService;
import com.yyh.knowledge.mapper.KnowledgeBaseMapper;
import com.yyh.knowledge.mapper.KnowledgeChunkMapper;
import com.yyh.knowledge.mapper.KnowledgeDocumentMapper;
import com.yyh.knowledge.minio.MinioService;
import com.yyh.knowledge.search.RetrievalService;
import com.yyh.knowledge.service.ChunkService;
import com.yyh.knowledge.service.DocumentParseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentAsyncProcessService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final MinioService minioService;
    private final DocumentParseService documentParseService;
    private final ChunkService chunkService;
    private final RetrievalService retrievalService;
    private final FaqCacheService faqCacheService;
    private final DistributedLockService distributedLockService;
    private final KnowledgeReliabilityProperties reliabilityProperties;

    @Transactional
    public void processUploadedDocument(Long kbId, Long docId) {
        DistributedLockService.LockHandle lockHandle = distributedLockService.tryLock(
                "process",
                kbId + ":" + docId,
                Duration.ofSeconds(Math.max(reliabilityProperties.getUploadLock().getProcessLeaseSeconds(), 30))
        );
        if (!lockHandle.isAcquired()) {
            log.info("文档处理锁已被占用，跳过重复处理: kbId={}, docId={}", kbId, docId);
            return;
        }
        KnowledgeDocument document = knowledgeDocumentMapper.selectById(docId);
        try {
            if (document == null || !kbId.equals(document.getKbId())) {
                log.warn("忽略不存在的文档异步处理事件: kbId={}, docId={}", kbId, docId);
                return;
            }
            if (document.getParseStatus() != null && document.getParseStatus() == 2) {
                log.info("文档已处理完成，跳过重复消费: kbId={}, docId={}", kbId, docId);
                return;
            }

            KnowledgeBase knowledgeBase = knowledgeBaseMapper.selectById(kbId);
            if (knowledgeBase == null) {
                markFailed(document, "知识库不存在");
                return;
            }

            document.setParseStatus(1);
            document.setErrorMsg(null);
            knowledgeDocumentMapper.updateById(document);

            retrievalService.deleteDocumentIndexes(kbId, docId);

            String parsedText;
            try (InputStream inputStream = minioService.getObjectByUrl(document.getFileUrl())) {
                parsedText = documentParseService.parse(document.getFileName(), inputStream);
            }

            List<KnowledgeChunk> chunks = chunkService.splitAndSave(kbId, docId, parsedText);
            retrievalService.indexChunks(kbId, chunks);

            document.setParseStatus(2);
            document.setChunkCount(chunks.size());
            document.setErrorMsg(null);
            knowledgeDocumentMapper.updateById(document);
            refreshKnowledgeBaseStats(kbId);
            faqCacheService.evictKnowledgeBaseCache(kbId);
        } catch (Exception ex) {
            retrievalService.deleteDocumentIndexes(kbId, docId);
            knowledgeChunkMapper.delete(
                    new LambdaQueryWrapper<KnowledgeChunk>().eq(KnowledgeChunk::getDocId, docId)
            );
            markFailed(document, ex.getMessage());
            refreshKnowledgeBaseStats(kbId);
            faqCacheService.evictKnowledgeBaseCache(kbId);
            log.error("文档异步处理失败: kbId={}, docId={}, message={}", kbId, docId, ex.getMessage(), ex);
        } finally {
            distributedLockService.unlock(lockHandle);
        }
    }

    private void markFailed(KnowledgeDocument document, String errorMessage) {
        document.setParseStatus(3);
        document.setErrorMsg(errorMessage);
        document.setChunkCount(defaultZero(document.getChunkCount()));
        knowledgeDocumentMapper.updateById(document);
    }

    private void refreshKnowledgeBaseStats(Long kbId) {
        KnowledgeBase knowledgeBase = knowledgeBaseMapper.selectById(kbId);
        if (knowledgeBase == null) {
            return;
        }
        long docCount = knowledgeDocumentMapper.selectCount(
                new LambdaQueryWrapper<KnowledgeDocument>().eq(KnowledgeDocument::getKbId, kbId)
        );
        long chunkCount = knowledgeChunkMapper.selectCount(
                new LambdaQueryWrapper<KnowledgeChunk>().eq(KnowledgeChunk::getKbId, kbId)
        );
        knowledgeBase.setDocCount((int) docCount);
        knowledgeBase.setChunkCount((int) chunkCount);
        knowledgeBaseMapper.updateById(knowledgeBase);
    }

    private int defaultZero(Integer value) {
        return value == null ? 0 : value;
    }
}
