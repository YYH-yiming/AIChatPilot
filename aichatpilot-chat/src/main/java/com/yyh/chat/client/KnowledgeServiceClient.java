package com.yyh.chat.client;

import com.yyh.chat.client.dto.KnowledgeAskClientRequest;
import com.yyh.chat.client.dto.KnowledgeAskClientResponse;
import com.yyh.chat.client.dto.KnowledgeBaseClientResponse;
import com.yyh.chat.client.dto.KnowledgeSearchHitClientResponse;
import com.yyh.chat.config.ChatProperties;
import com.yyh.chat.support.SecurityUtils;
import com.yyh.common.exception.BusinessException;
import com.yyh.common.result.Result;
import com.yyh.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
@RequiredArgsConstructor
public class KnowledgeServiceClient {

    private final ChatProperties chatProperties;

    public KnowledgeAskClientResponse ask(Long kbId, String query, Integer topK) {
        if (kbId == null || kbId <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "knowledge模式下必须提供kbId");
        }

        Result<KnowledgeAskClientResponse> result = buildClient().post()
                .uri(chatProperties.getKnowledge().getServiceUrl() + "/api/knowledge/bases/{id}/ask", kbId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new KnowledgeAskClientRequest(query, topK))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });

        if (result == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "知识库服务返回为空");
        }
        if (result.getCode() != ResultCode.SUCCESS.getCode() || result.getData() == null) {
            String message = StringUtils.hasText(result.getMessage()) ? result.getMessage() : "知识库服务调用失败";
            throw new BusinessException(result.getCode(), message);
        }
        return result.getData();
    }

    public List<KnowledgeBaseClientResponse> listKnowledgeBases() {
        Result<List<KnowledgeBaseClientResponse>> result = buildClient().get()
                .uri(chatProperties.getKnowledge().getServiceUrl() + "/api/knowledge/bases")
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });

        if (result == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "知识库列表返回为空");
        }
        if (result.getCode() != ResultCode.SUCCESS.getCode() || result.getData() == null) {
            String message = StringUtils.hasText(result.getMessage()) ? result.getMessage() : "知识库列表调用失败";
            throw new BusinessException(result.getCode(), message);
        }
        return result.getData();
    }

    public List<KnowledgeSearchHitClientResponse> search(Long kbId, String query, Integer topK) {
        if (kbId == null || kbId <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "knowledge搜索必须提供kbId");
        }

        Result<List<KnowledgeSearchHitClientResponse>> result = buildClient().post()
                .uri(chatProperties.getKnowledge().getServiceUrl() + "/api/knowledge/bases/{id}/search", kbId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new KnowledgeAskClientRequest(query, topK))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });

        if (result == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "知识库检索返回为空");
        }
        if (result.getCode() != ResultCode.SUCCESS.getCode() || result.getData() == null) {
            String message = StringUtils.hasText(result.getMessage()) ? result.getMessage() : "知识库检索调用失败";
            throw new BusinessException(result.getCode(), message);
        }
        return result.getData();
    }

    private RestClient buildClient() {
        return RestClient.builder()
                .defaultHeader("X-User-Id", String.valueOf(SecurityUtils.currentUserId()))
                .defaultHeader("X-Tenant-Id", String.valueOf(SecurityUtils.currentTenantId()))
                .build();
    }
}
