package com.yyh.knowledge.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.yyh.knowledge.dto.KnowledgeSearchHitVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 检索后精排（rerank）。对召回层给出的候选切片用 Cross-Encoder 重排，提升 top1/top3 相关性。
 * 默认对接 SiliconFlow 的 /v1/rerank（BAAI/bge-reranker-v2-m3，与现有 embedding 同厂）。
 * 未配置或调用失败时降级为候选原序前 topK，保持与其它检索组件一致的容错风格。
 */
@Slf4j
@Service
public class RerankService {

    @Value("${rerank.api-url:}")
    private String apiUrl;

    @Value("${rerank.api-key:}")
    private String apiKey;

    @Value("${rerank.model:BAAI/bge-reranker-v2-m3}")
    private String model;

    public List<KnowledgeSearchHitVO> rerank(String query, List<KnowledgeSearchHitVO> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            return candidates == null ? List.of() : candidates;
        }
        if (!StringUtils.hasText(apiUrl) || !StringUtils.hasText(apiKey)) {
            log.debug("Rerank未配置(rerank.api-url/api-key)，跳过重排");
            return head(candidates, topK);
        }
        try {
            List<String> documents = candidates.stream()
                    .map(hit -> hit.getContent() == null ? "" : hit.getContent())
                    .toList();
            JsonNode response = client().post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "model", model,
                            "query", query,
                            "documents", documents,
                            "top_n", Math.min(topK, documents.size())
                    ))
                    .retrieve()
                    .body(JsonNode.class);
            List<KnowledgeSearchHitVO> reordered = reorder(candidates, response);
            return head(reordered.isEmpty() ? candidates : reordered, topK);
        } catch (Exception ex) {
            log.warn("Rerank失败，降级为融合排序: {}", ex.getMessage());
            return head(candidates, topK);
        }
    }

    /**
     * 解析 rerank 响应（results[].index / relevance_score，SiliconFlow / Cohere 风格）并按相关性重排。
     * 抽成包级可见方法，便于单测，不依赖真实 HTTP。
     */
    List<KnowledgeSearchHitVO> reorder(List<KnowledgeSearchHitVO> candidates, JsonNode response) {
        if (response == null) {
            return List.of();
        }
        JsonNode results = response.path("results");
        if (!results.isArray() || results.isEmpty()) {
            return List.of();
        }
        List<KnowledgeSearchHitVO> out = new ArrayList<>();
        for (JsonNode item : results) {
            int idx = item.path("index").asInt(-1);
            if (idx < 0 || idx >= candidates.size()) {
                continue;
            }
            KnowledgeSearchHitVO vo = candidates.get(idx);
            if (item.has("relevance_score")) {
                vo.setScore(item.path("relevance_score").asDouble(vo.getScore() == null ? 0D : vo.getScore()));
            }
            out.add(vo);
        }
        return out;
    }

    private List<KnowledgeSearchHitVO> head(List<KnowledgeSearchHitVO> list, int topK) {
        return list.size() > topK ? new ArrayList<>(list.subList(0, topK)) : list;
    }

    private RestClient client() {
        return RestClient.builder()
                .baseUrl(apiUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
