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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalService {

    private final EmbeddingService embeddingService;
    private final MilvusService milvusService;
    private final ElasticsearchService elasticsearchService;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final RerankService rerankService;

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

    @Value("${knowledge.retrieval.rerank-enabled:true}")
    private boolean rerankEnabled;

    @Value("${knowledge.retrieval.recall-top-n:20}")
    private int recallTopN;

    @Value("${knowledge.chunk.parent-child.enabled:false}")
    private boolean parentChildEnabled;

    /** 父子模式召回放大系数：召回 limit×该值 个子块，子→父去重后才凑得满 topK 个不同父块（dense 子块常聚同篇，不放大会压低 recall）。 */
    @Value("${knowledge.chunk.parent-child.child-recall-multiplier:5}")
    private int childRecallMultiplier;

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
        return search(query, kbId, topK, null);
    }

    /**
     * 带按请求覆盖项的检索：召回层（dense/sparse + RRF，可加宽到 recallN）+ 可选 rerank 精排到 topK。
     * options 为 null 或字段为 null 时回退全局配置默认值，行为与旧版一致。
     */
    public List<KnowledgeSearchHitVO> search(String query, Long kbId, Integer topK, RetrievalOptions options) {
        int limit = topK == null ? defaultTopK : topK;
        boolean useDense = options != null && options.dense() != null ? options.dense() : denseEnabled;
        boolean useSparse = options != null && options.sparse() != null ? options.sparse() : sparseEnabled;
        boolean useRerank = options != null && options.rerank() != null ? options.rerank() : rerankEnabled;
        int recallN = options != null && options.recallTopN() != null
                ? options.recallTopN()
                : (useRerank ? recallTopN : limit);
        recallN = Math.max(recallN, limit);
        if (parentChildEnabled) {
            // 父子模式：子块命中要按 parentId 去重成父块，候选必须多捞，否则去重后凑不满 topK 个不同父块（recall 被压低）。
            recallN = Math.max(recallN, limit * Math.max(childRecallMultiplier, 1));
        }

        long recallStart = System.nanoTime();
        List<KnowledgeSearchHitVO> candidates = retrieveCandidates(query, kbId, useDense, useSparse, recallN);
        long recallMs = elapsedMs(recallStart);
        if (candidates.isEmpty()) {
            log.info("[PERF] stage=search kbId={} topK={} dense={} sparse={} rerank={} recallMs={} rerankMs=-1 hit=fallback",
                    kbId, limit, useDense, useSparse, useRerank, recallMs);
            return mapChildrenToParents(fallbackSearch(query, kbId, limit));
        }
        long rerankMs = -1L;
        if (useRerank) {
            long rerankStart = System.nanoTime();
            candidates = rerankService.rerank(query, candidates, recallN);
            rerankMs = elapsedMs(rerankStart);
        }
        log.info("[PERF] stage=search kbId={} topK={} dense={} sparse={} rerank={} recallMs={} rerankMs={} candidates={}",
                kbId, limit, useDense, useSparse, useRerank, recallMs, rerankMs, candidates.size());
        // 先把（全部精排后的）子块按 parentId 去重映射成父块，再取 top-K 个【不同父块】——
        // 保证父子模式下也能凑足 K 个 ref，doc 级口径与平铺模式一致、对 PC 公平。
        List<KnowledgeSearchHitVO> mapped = mapChildrenToParents(candidates);
        if (mapped.size() > limit) {
            return new ArrayList<>(mapped.subList(0, limit));
        }
        return mapped;
    }

    private List<KnowledgeSearchHitVO> retrieveCandidates(String query, Long kbId, boolean useDense, boolean useSparse, int recallN) {
        Map<Long, RetrievalScore> merged = new LinkedHashMap<>();
        long embedMs = -1L;
        long denseMs = -1L;
        long sparseMs = -1L;

        if (useDense) {
            try {
                long embedStart = System.nanoTime();
                float[] queryVector = embeddingService.embed(query);
                embedMs = elapsedMs(embedStart);
                long denseStart = System.nanoTime();
                Map<Long, Double> denseResults = milvusService.search(kbId, queryVector, recallN);
                denseMs = elapsedMs(denseStart);
                mergeRankScores(merged, denseResults, ScoreSource.DENSE);
            } catch (Exception ex) {
                log.warn("Milvus检索失败，将忽略稠密检索结果: {}", ex.getMessage());
            }
        }

        if (useSparse) {
            try {
                long sparseStart = System.nanoTime();
                Map<Long, Double> sparseResults = elasticsearchService.search(kbId, query, recallN);
                sparseMs = elapsedMs(sparseStart);
                mergeRankScores(merged, sparseResults, ScoreSource.SPARSE);
            } catch (Exception ex) {
                log.warn("ES检索失败，将忽略稀疏检索结果: {}", ex.getMessage());
            }
        }

        log.info("[PERF] stage=recall kbId={} recallN={} embedMs={} denseMs={} sparseMs={} merged={}",
                kbId, recallN, embedMs, denseMs, sparseMs, merged.size());

        List<Long> chunkIds = merged.entrySet().stream()
                .sorted(Comparator.comparingDouble((Map.Entry<Long, RetrievalScore> entry) -> entry.getValue().score()).reversed())
                .limit(recallN)
                .map(Map.Entry::getKey)
                .toList();

        if (chunkIds.isEmpty()) {
            return new ArrayList<>();
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
            vo.setParentId(chunk.getParentId());
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

    /**
     * 子块命中 → 返回父块（small-to-big）：按 parentId 去重、取父块内容返回；parentId 为空（旧平铺块）原样透传。
     * 始终运行：父子切分未启用时所有块 parentId=null，本方法等价于原样返回、行为不变。
     */
    private List<KnowledgeSearchHitVO> mapChildrenToParents(List<KnowledgeSearchHitVO> hits) {
        List<Long> parentIds = hits.stream()
                .map(KnowledgeSearchHitVO::getParentId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (parentIds.isEmpty()) {
            return hits;
        }
        Map<Long, KnowledgeChunk> parents = knowledgeChunkMapper.selectBatchIds(parentIds).stream()
                .collect(Collectors.toMap(KnowledgeChunk::getId, chunk -> chunk));
        List<KnowledgeSearchHitVO> result = new ArrayList<>();
        Set<Long> emitted = new HashSet<>();
        for (KnowledgeSearchHitVO hit : hits) {
            Long parentId = hit.getParentId();
            if (parentId == null) {
                result.add(hit); // 旧平铺块：原样
                continue;
            }
            if (!emitted.add(parentId)) {
                continue; // 同一父块的多个子块命中 → 去重
            }
            KnowledgeChunk parent = parents.get(parentId);
            if (parent == null) {
                result.add(hit); // 父块缺失兜底用子块
                continue;
            }
            KnowledgeSearchHitVO vo = new KnowledgeSearchHitVO();
            vo.setChunkId(parent.getId());
            vo.setParentId(null);
            vo.setDocId(parent.getDocId());
            vo.setKbId(parent.getKbId());
            vo.setChunkIndex(parent.getChunkIndex());
            vo.setTokenCount(parent.getTokenCount());
            vo.setContent(parent.getContent()); // 返回父块完整内容给 RAG
            vo.setScore(hit.getScore());         // 沿用命中子块的分数/来源
            vo.setDenseScore(hit.getDenseScore());
            vo.setSparseScore(hit.getSparseScore());
            vo.setSource(hit.getSource());
            result.add(vo);
        }
        return result;
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
                    vo.setParentId(chunk.getParentId());
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

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
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
