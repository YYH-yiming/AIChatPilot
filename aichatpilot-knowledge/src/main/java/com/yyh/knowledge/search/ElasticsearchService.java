package com.yyh.knowledge.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.yyh.common.exception.BusinessException;
import com.yyh.common.result.ResultCode;
import com.yyh.knowledge.entity.KnowledgeChunk;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ElasticsearchService {

    @Value("${spring.elasticsearch.uris:http://localhost:9200}")
    private String elasticsearchUri;

    @Value("${spring.elasticsearch.username:}")
    private String username;

    @Value("${spring.elasticsearch.password:}")
    private String password;

    @Value("${knowledge.retrieval.sparse-enabled:true}")
    private boolean sparseEnabled;

    public void indexChunks(Long kbId, List<KnowledgeChunk> chunks) {
        if (!sparseEnabled || chunks.isEmpty()) {
            return;
        }
        ensureIndex(kbId);
        RestClient client = client();
        for (KnowledgeChunk chunk : chunks) {
            client.put()
                    .uri("/" + indexName(kbId) + "/_doc/" + chunk.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "chunkId", chunk.getId(),
                            "docId", chunk.getDocId(),
                            "kbId", chunk.getKbId(),
                            "chunkIndex", chunk.getChunkIndex(),
                            "tokenCount", chunk.getTokenCount(),
                            "content", chunk.getContent()
                    ))
                    .retrieve()
                    .toBodilessEntity();
        }
    }

    public Map<Long, Double> search(Long kbId, String query, int topK) {
        if (!sparseEnabled) {
            return Map.of();
        }
        ensureIndex(kbId);
        JsonNode response = client().post()
                .uri("/" + indexName(kbId) + "/_search")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "size", topK,
                        "query", Map.of(
                                "match", Map.of(
                                        "content", Map.of("query", query)
                                )
                        )
                ))
                .retrieve()
                .body(JsonNode.class);

        Map<Long, Double> results = new LinkedHashMap<>();
        JsonNode hits = response == null ? null : response.path("hits").path("hits");
        if (hits != null && hits.isArray()) {
            for (JsonNode hit : hits) {
                JsonNode source = hit.path("_source");
                if (!source.has("chunkId")) {
                    continue;
                }
                results.put(source.path("chunkId").asLong(), hit.path("_score").asDouble(0D));
            }
        }
        return results;
    }

    public void deleteByDocId(Long kbId, Long docId) {
        if (!sparseEnabled) {
            return;
        }
        ensureIndex(kbId);
        client().post()
                .uri("/" + indexName(kbId) + "/_delete_by_query")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "query", Map.of(
                                "term", Map.of("docId", docId)
                        )
                ))
                .retrieve()
                .toBodilessEntity();
    }

    public void deleteIndex(Long kbId) {
        if (!sparseEnabled) {
            return;
        }
        try {
            client().delete()
                    .uri("/" + indexName(kbId))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() != 404) {
                throw ex;
            }
        }
    }

    private void ensureIndex(Long kbId) {
        try {
            client().put()
                    .uri("/" + indexName(kbId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "mappings", Map.of(
                                    "properties", Map.of(
                                            "chunkId", Map.of("type", "long"),
                                            "docId", Map.of("type", "long"),
                                            "kbId", Map.of("type", "long"),
                                            "chunkIndex", Map.of("type", "integer"),
                                            "tokenCount", Map.of("type", "integer"),
                                            "content", Map.of("type", "text")
                                    )
                            )
                    ))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            if (!ex.getResponseBodyAsString().contains("resource_already_exists_exception")) {
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "创建ES索引失败: " + ex.getMessage());
            }
        }
    }

    private RestClient client() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(elasticsearchUri)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        if (StringUtils.hasText(username) && StringUtils.hasText(password)) {
            String basic = java.util.Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basic);
        }
        return builder.build();
    }

    private String indexName(Long kbId) {
        return "knowledge-kb-" + kbId;
    }
}
