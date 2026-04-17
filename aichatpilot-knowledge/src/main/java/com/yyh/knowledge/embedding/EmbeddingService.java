package com.yyh.knowledge.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.yyh.common.exception.BusinessException;
import com.yyh.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class EmbeddingService {

    @Value("${embedding.api-url}")
    private String apiUrl;

    @Value("${embedding.api-key:}")
    private String apiKey;

    @Value("${embedding.model}")
    private String model;

    public float[] embed(String text) {
        List<float[]> embeddings = embedBatch(List.of(text));
        if (embeddings.isEmpty()) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "Embedding服务未返回向量结果");
        }
        return embeddings.get(0);
    }

    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        if (!StringUtils.hasText(apiKey)) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "缺少EMBEDDING_API_KEY配置");
        }

        RestClient client = RestClient.builder()
                .baseUrl(apiUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("input", texts);

        JsonNode response = client.post()
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(JsonNode.class);

        if (response == null || !response.has("data") || !response.get("data").isArray()) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "Embedding接口返回格式不正确");
        }

        List<float[]> results = new ArrayList<>();
        for (JsonNode item : response.get("data")) {
            JsonNode embeddingNode = item.get("embedding");
            if (embeddingNode == null || !embeddingNode.isArray()) {
                continue;
            }
            float[] vector = new float[embeddingNode.size()];
            for (int i = 0; i < embeddingNode.size(); i++) {
                vector[i] = embeddingNode.get(i).floatValue();
            }
            results.add(vector);
        }
        if (results.isEmpty()) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "Embedding接口未返回有效向量");
        }
        return results;
    }
}
