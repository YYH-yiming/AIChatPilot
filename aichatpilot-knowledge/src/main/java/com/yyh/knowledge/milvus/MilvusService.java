package com.yyh.knowledge.milvus;

import com.fasterxml.jackson.databind.JsonNode;
import com.yyh.common.exception.BusinessException;
import com.yyh.common.result.ResultCode;
import com.yyh.knowledge.entity.KnowledgeChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class MilvusService {

    @Value("${milvus.host}")
    private String host;

    @Value("${milvus.port}")
    private int port;

    @Value("${milvus.username:}")
    private String username;

    @Value("${milvus.password:}")
    private String password;

    @Value("${milvus.database:default}")
    private String database;

    @Value("${knowledge.retrieval.dense-enabled:true}")
    private boolean denseEnabled;

    public void upsert(Long kbId, List<KnowledgeChunk> chunks, List<float[]> vectors) {
        if (!denseEnabled || chunks.isEmpty()) {
            return;
        }
        if (chunks.size() != vectors.size()) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "切片数量与向量数量不一致");
        }

        ensureCollection(kbId, vectors.get(0).length);
        List<Map<String, Object>> data = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", vectorId(chunks.get(i)));
            row.put("embedding", vectors.get(i));
            data.add(row);
        }

        JsonNode response = client().post()
                .uri("/v2/vectordb/entities/upsert")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "dbName", database,
                        "collectionName", collectionName(kbId),
                        "data", data
                ))
                .retrieve()
                .body(JsonNode.class);
        ensureSuccess(response, "Milvus upsert");
    }

    public Map<Long, Double> search(Long kbId, float[] queryVector, int topK) {
        if (!denseEnabled) {
            return Map.of();
        }
        ensureCollection(kbId, queryVector.length);
        JsonNode response = client().post()
                .uri("/v2/vectordb/entities/search")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "dbName", database,
                        "collectionName", collectionName(kbId),
                        "annsField", "embedding",
                        "data", List.of(queryVector),
                        "limit", topK,
                        "outputFields", List.of("id")
                ))
                .retrieve()
                .body(JsonNode.class);
        ensureSuccess(response, "Milvus search");

        Map<Long, Double> results = new LinkedHashMap<>();
        JsonNode data = response.path("data");
        if (data.isArray() && !data.isEmpty()) {
            JsonNode hits = data.get(0).isArray() ? data.get(0) : data;
            for (JsonNode hit : hits) {
                if (hit.path("id").isMissingNode()) {
                    continue;
                }
                Long chunkId = parseChunkId(hit.path("id").asText());
                if (chunkId == null) {
                    continue;
                }
                // Milvus v2 REST 相似度字段是 distance（COSINE 下即相似度，越大越相关）；兼容老版本的 score，最后兜底 0。
                double score = hit.path("distance").asDouble(hit.path("score").asDouble(0D));
                results.put(chunkId, score);
            }
        }
        return results;
    }

    public void deleteByVectorIds(Long kbId, List<String> vectorIds) {
        if (!denseEnabled || vectorIds == null || vectorIds.isEmpty()) {
            return;
        }
        JsonNode response = client().post()
                .uri("/v2/vectordb/entities/delete")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "dbName", database,
                        "collectionName", collectionName(kbId),
                        "ids", new ArrayList<>(new LinkedHashSet<>(vectorIds))
                ))
                .retrieve()
                .body(JsonNode.class);
        ensureSuccess(response, "Milvus delete");
    }

    public void dropCollection(Long kbId) {
        if (!denseEnabled || !hasCollection(kbId)) {
            return;
        }
        JsonNode response = client().post()
                .uri("/v2/vectordb/collections/drop")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "dbName", database,
                        "collectionName", collectionName(kbId)
                ))
                .retrieve()
                .body(JsonNode.class);
        ensureSuccess(response, "Milvus drop collection");
    }

    private void ensureCollection(Long kbId, int dimension) {
        if (hasCollection(kbId)) {
            return;
        }

        JsonNode createResponse = client().post()
                .uri("/v2/vectordb/collections/create")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "dbName", database,
                        "collectionName", collectionName(kbId),
                        "dimension", dimension,
                        "metricType", "COSINE",
                        "idType", "VarChar",
                        "autoId", false,
                        "primaryFieldName", "id",
                        "vectorFieldName", "embedding",
                        "params", Map.of(
                                "max_length", "64"
                        )
                ))
                .retrieve()
                .body(JsonNode.class);
        ensureSuccess(createResponse, "Milvus create collection");

        JsonNode loadResponse = client().post()
                .uri("/v2/vectordb/collections/load")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "dbName", database,
                        "collectionName", collectionName(kbId)
                ))
                .retrieve()
                .body(JsonNode.class);
        ensureSuccess(loadResponse, "Milvus load collection");
    }

    private boolean hasCollection(Long kbId) {
        JsonNode response = client().post()
                .uri("/v2/vectordb/collections/has")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "dbName", database,
                        "collectionName", collectionName(kbId)
                ))
                .retrieve()
                .body(JsonNode.class);
        ensureSuccess(response, "Milvus has collection");
        return response.path("data").path("has").asBoolean(false);
    }

    private RestClient client() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("http://" + host + ":" + port)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        if (StringUtils.hasText(username) && StringUtils.hasText(password)) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + username + ":" + password);
        }
        return builder.build();
    }

    private void ensureSuccess(JsonNode response, String action) {
        if (response == null || response.path("code").asInt(-1) != 0) {
            String message = response == null ? "无响应" : response.path("message").asText("未知错误");
            throw new BusinessException(ResultCode.INTERNAL_ERROR, action + "失败: " + message);
        }
    }

    private String collectionName(Long kbId) {
        return "knowledge_kb_" + kbId;
    }

    private String vectorId(KnowledgeChunk chunk) {
        return StringUtils.hasText(chunk.getVectorId()) ? chunk.getVectorId() : String.valueOf(chunk.getId());
    }

    private Long parseChunkId(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            log.warn("Milvus返回的向量ID无法解析为chunkId: {}", value);
            return null;
        }
    }
}
