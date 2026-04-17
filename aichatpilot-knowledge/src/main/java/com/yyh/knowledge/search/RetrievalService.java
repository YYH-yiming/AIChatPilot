package com.yyh.knowledge.search;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yyh.knowledge.dto.KnowledgeSearchHitVO;
import com.yyh.knowledge.embedding.EmbeddingService;
import com.yyh.knowledge.entity.KnowledgeChunk;
import com.yyh.knowledge.mapper.KnowledgeChunkMapper;
import com.yyh.knowledge.milvus.MilvusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalService {

    private final EmbeddingService embeddingService;
    private final MilvusService milvusService;
    private final ElasticsearchService elasticsearchService;
    private final KnowledgeChunkMapper knowledgeChunkMapper;

    @Value("${knowledge.retrieval.top-k:5}")
    private int defaultTopK;

    @Value("${knowledge.retrieval.rrf-k:60}")
    private int rrfK;

    @Value("${knowledge.retrieval.index-on-upload:true}")
    private boolean indexOnUpload;

    @Value("${knowledge.retrieval.fail-on-index-error:false}")
    private boolean failOnIndexError;

    @Value("${knowledge.retrieval.dense-enabled:true}")
    private boolean denseEnabled;

    @Value("${knowledge.retrieval.sparse-enabled:true}")
    private boolean sparseEnabled;

    public void indexChunks(Long kbId, List<KnowledgeChunk> chunks) {
        if (!indexOnUpload || chunks == null || chunks.isEmpty()) {
            return;
        }

        if (denseEnabled) {
            executeIndexStep("Milvus向量索引", () -> {
                List<float[]> vectors = embeddingService.embedBatch(
                        chunks.stream().map(KnowledgeChunk::getContent).toList()
                );
                milvusService.upsert(kbId, chunks, vectors);
            });
        }

        if (sparseEnabled) {
            executeIndexStep("Elasticsearch索引", () -> elasticsearchService.indexChunks(kbId, chunks));
        }
    }

    public List<KnowledgeSearchHitVO> search(String query, Long kbId, Integer topK) {
        int limit = topK == null ? defaultTopK : topK;
        Map<Long, RetrievalScore> merged = new LinkedHashMap<>();

        if (denseEnabled) {
            try {
                Map<Long, Double> denseResults = milvusService.search(kbId, embeddingService.embed(query), limit);
                mergeRankScores(merged, denseResults, ScoreSource.DENSE);
            } catch (Exception ex) {
                log.warn("Milvus检索失败，将忽略稠密检索结果: {}", ex.getMessage());
            }
        }

        if (sparseEnabled) {
            try {
                Map<Long, Double> sparseResults = elasticsearchService.search(kbId, query, limit);
                mergeRankScores(merged, sparseResults, ScoreSource.SPARSE);
            } catch (Exception ex) {
                log.warn("ES检索失败，将忽略稀疏检索结果: {}", ex.getMessage());
            }
        }

        List<Long> chunkIds = merged.entrySet().stream()
                .sorted(Comparator.comparingDouble((Map.Entry<Long, RetrievalScore> entry) -> entry.getValue().score()).reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();

        if (chunkIds.isEmpty()) {
            return fallbackSearch(query, kbId, limit);
        }

        Map<Long, KnowledgeChunk> chunkMap = knowledgeChunkMapper.selectBatchIds(chunkIds).stream()
                .collect(Collectors.toMap(KnowledgeChunk::getId, chunk -> chunk));

        List<KnowledgeSearchHitVO> results = new ArrayList<>();
        for (Long chunkId : chunkIds) {
            KnowledgeChunk chunk = chunkMap.get(chunkId);
            if (chunk == null) {
                continue;
            }
            RetrievalScore score = merged.get(chunkId);
            KnowledgeSearchHitVO vo = new KnowledgeSearchHitVO();
            vo.setChunkId(chunk.getId());
            vo.setDocId(chunk.getDocId());
            vo.setKbId(chunk.getKbId());
            vo.setChunkIndex(chunk.getChunkIndex());
            vo.setTokenCount(chunk.getTokenCount());
            vo.setContent(chunk.getContent());
            vo.setScore(score.score());
            vo.setDenseScore(score.denseScore);
            vo.setSparseScore(score.sparseScore);
            vo.setSource(score.source());
            results.add(vo);
        }
        return results;
    }

    public void deleteDocumentIndexes(Long kbId, Long docId) {
        List<KnowledgeChunk> chunks = knowledgeChunkMapper.selectList(
                new LambdaQueryWrapper<KnowledgeChunk>()
                        .eq(KnowledgeChunk::getKbId, kbId)
                        .eq(KnowledgeChunk::getDocId, docId)
        );
        if (chunks.isEmpty()) {
            return;
        }
        List<String> vectorIds = chunks.stream()
                .map(chunk -> chunk.getVectorId() == null ? String.valueOf(chunk.getId()) : chunk.getVectorId())
                .toList();

        if (denseEnabled) {
            executeDeleteStep("Milvus删除文档向量", () -> milvusService.deleteByVectorIds(kbId, vectorIds));
        }
        if (sparseEnabled) {
            executeDeleteStep("ES删除文档切片", () -> elasticsearchService.deleteByDocId(kbId, docId));
        }
    }

    public void deleteKnowledgeBaseIndexes(Long kbId) {
        if (denseEnabled) {
            executeDeleteStep("Milvus删除知识库集合", () -> milvusService.dropCollection(kbId));
        }
        if (sparseEnabled) {
            executeDeleteStep("ES删除知识库索引", () -> elasticsearchService.deleteIndex(kbId));
        }
    }

    private void mergeRankScores(Map<Long, RetrievalScore> merged, Map<Long, Double> result, ScoreSource source) {
        int rank = 1;
        for (Map.Entry<Long, Double> entry : result.entrySet()) {
            RetrievalScore score = merged.computeIfAbsent(entry.getKey(), ignored -> new RetrievalScore());
            score.score += 1D / (rrfK + rank);
            if (source == ScoreSource.DENSE) {
                score.denseScore = entry.getValue();
            } else {
                score.sparseScore = entry.getValue();
            }
            rank++;
        }
    }

    private List<KnowledgeSearchHitVO> fallbackSearch(String query, Long kbId, int limit) {
        return knowledgeChunkMapper.selectList(
                        new LambdaQueryWrapper<KnowledgeChunk>()
                                .eq(KnowledgeChunk::getKbId, kbId)
                                .like(KnowledgeChunk::getContent, query)
                                .last("limit " + limit)
                ).stream()
                .map(chunk -> {
                    KnowledgeSearchHitVO vo = new KnowledgeSearchHitVO();
                    vo.setChunkId(chunk.getId());
                    vo.setDocId(chunk.getDocId());
                    vo.setKbId(chunk.getKbId());
                    vo.setChunkIndex(chunk.getChunkIndex());
                    vo.setTokenCount(chunk.getTokenCount());
                    vo.setContent(chunk.getContent());
                    vo.setScore(0D);
                    vo.setDenseScore(0D);
                    vo.setSparseScore(0D);
                    vo.setSource("db");
                    return vo;
                })
                .toList();
    }

    private void executeIndexStep(String action, Runnable step) {
        try {
            step.run();
        } catch (Exception ex) {
            if (failOnIndexError) {
                throw ex;
            }
            log.warn("{}失败，已按降级策略忽略: {}", action, ex.getMessage());
        }
    }

    private void executeDeleteStep(String action, Runnable step) {
        try {
            step.run();
        } catch (Exception ex) {
            log.warn("{}失败，已忽略: {}", action, ex.getMessage());
        }
    }

    private enum ScoreSource {
        DENSE,
        SPARSE
    }

    private static final class RetrievalScore {
        private double score;
        private Double denseScore;
        private Double sparseScore;

        public double score() {
            return score;
        }

        public String source() {
            if (denseScore != null && sparseScore != null) {
                return "hybrid";
            }
            if (denseScore != null) {
                return "dense";
            }
            if (sparseScore != null) {
                return "sparse";
            }
            return "unknown";
        }
    }
}
