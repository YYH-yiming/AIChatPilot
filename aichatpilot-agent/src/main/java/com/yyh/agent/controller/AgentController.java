package com.yyh.agent.controller;

import com.yyh.agent.dto.AgentRequest;
import com.yyh.agent.dto.AgentResponse;
import com.yyh.agent.service.AgentChatService;
import com.yyh.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Agent智能路由")
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentChatService agentChatService;

    @Operation(summary = "统一Agent对话入口")
    @PostMapping("/chat")
    public Result<AgentResponse> chat(@RequestBody @Valid AgentRequest request) {
        return Result.success(agentChatService.chat(request));
    }
}
