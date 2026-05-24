package com.yyh.chat.client;

import com.yyh.chat.client.dto.AgentChatClientRequest;
import com.yyh.chat.client.dto.AgentChatClientResponse;
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

@Component
@RequiredArgsConstructor
public class AgentServiceClient {

    private final ChatProperties chatProperties;

    public AgentChatClientResponse chat(AgentChatClientRequest request) {
        Result<AgentChatClientResponse> result = buildClient().post()
                .uri(chatProperties.getAgent().getServiceUrl() + "/api/agent/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });

        if (result == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "Agent服务返回为空");
        }
        if (result.getCode() != ResultCode.SUCCESS.getCode() || result.getData() == null) {
            String message = StringUtils.hasText(result.getMessage()) ? result.getMessage() : "Agent服务调用失败";
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
