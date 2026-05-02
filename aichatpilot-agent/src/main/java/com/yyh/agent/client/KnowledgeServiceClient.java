package com.yyh.agent.client;

import com.yyh.agent.client.dto.KnowledgeAskClientResponse;
import com.yyh.agent.client.dto.KnowledgeSearchHitClientResponse;
import com.yyh.agent.config.AgentProperties;
import com.yyh.agent.support.SecurityUtils;
import com.yyh.common.exception.BusinessException;
import com.yyh.common.result.Result;
import com.yyh.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;

@Component
@RequiredArgsConstructor
public class KnowledgeServiceClient {

    private final AgentProperties agentProperties;

    public KnowledgeAskClientResponse ask(Long kbId, String query, Integer topK) {
        Result<KnowledgeAskClientResponse> result = buildClient().post()
                .uri(agentProperties.getKnowledge().getServiceUrl() + "/api/knowledge/bases/{id}/ask", kbId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(buildAskRequest(query, topK))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        return unwrap(result, "知识库问答调用失败");
    }

    public List<KnowledgeSearchHitClientResponse> search(Long kbId, String query, Integer topK) {
        Result<List<KnowledgeSearchHitClientResponse>> result = buildClient().post()
                .uri(agentProperties.getKnowledge().getServiceUrl() + "/api/knowledge/bases/{id}/search", kbId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(buildSearchRequest(query, topK))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        return unwrap(result, "知识库检索调用失败");
    }

    private RestClient buildClient() {
        return RestClient.builder()
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("X-User-Id", String.valueOf(SecurityUtils.currentUserId()))
                .defaultHeader("X-Tenant-Id", String.valueOf(SecurityUtils.currentTenantId()))
                .build();
    }

    private LinkedHashMap<String, Object> buildAskRequest(String query, Integer topK) {
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        if (topK != null) {
            body.put("topK", topK);
        }
        return body;
    }

    private LinkedHashMap<String, Object> buildSearchRequest(String query, Integer topK) {
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("topK", topK == null ? agentProperties.getKnowledge().getSearchTopK() : topK);
        return body;
    }

    private <T> T unwrap(Result<T> result, String fallbackMessage) {
        if (result == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, fallbackMessage);
        }
        if (result.getCode() != ResultCode.SUCCESS.getCode()) {
            String message = StringUtils.hasText(result.getMessage()) ? result.getMessage() : fallbackMessage;
            throw new BusinessException(ResultCode.INTERNAL_ERROR, message);
        }
        return result.getData();
    }
}
