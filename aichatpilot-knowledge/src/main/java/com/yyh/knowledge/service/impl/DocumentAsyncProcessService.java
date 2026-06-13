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
import com.yyh.knowledge.parser.ParsedDocument;
import com.yyh.knowledge.parser.StructuredDocumentParser;
import com.yyh.knowledge.search.RetrievalService;
import com.yyh.knowledge.service.ChunkService;
import com.yyh.knowledge.service.DocumentParseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
    private final StructuredDocumentParser structuredDocumentParser;
    private final ParentChildChunkService parentChildChunkService;

    @Value("${knowledge.chunk.parent-child.enabled:false}")
    private boolean parentChildEnabled;

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

            List<KnowledgeChunk> indexed;
            if (parentChildEnabled) {
                // 父子切分：结构化解析 → 父~1500/子~300 → 只把【子块】喂索引（父块只入库，命中后返回）
                byte[] fileBytes;
                try (InputStream inputStream = minioService.getObjectByUrl(document.getFileUrl())) {
                    fileBytes = inputStream.readAllBytes();
                }
                ParsedDocument parsed = structuredDocumentParser.parse(document.getFileName(), fileBytes);
                indexed = parentChildChunkService.splitAndSave(kbId, docId, parsed);
            } else {
                // flat 臂：与父子臂【同一套结构化解析】(StructuredDocumentParser/PDFBox)，仅切分层走平铺。
                // 这样两臂"只差切分、解析一致"，对比才纯；同时绕开老 Tika 解析 PDF 在 Tabula 引入后
                // 的 PDFBox 版本冲突(抛 Error 透出 500、且事务回滚前已删向量)。
                byte[] fileBytes;
                try (InputStream inputStream = minioService.getObjectByUrl(document.getFileUrl())) {
                    fileBytes = inputStream.readAllBytes();
                }
                String parsedText = structuredDocumentParser.parse(document.getFileName(), fileBytes).toMarkdown();
                indexed = chunkService.splitAndSave(kbId, docId, parsedText);
            }
            retrievalService.indexChunks(kbId, indexed);

            document.setParseStatus(2);
            document.setChunkCount(indexed.size());
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
