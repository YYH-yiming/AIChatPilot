package com.yyh.agent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AgentRequest {

    @NotBlank(message = "query不能为空")
    private String query;

    private Long sessionId;
    private Long kbId;
    private Long tenantId;
}
