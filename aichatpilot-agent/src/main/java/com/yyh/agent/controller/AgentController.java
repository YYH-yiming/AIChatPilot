package com.yyh.agent.controller;

import com.yyh.agent.dto.AgentRequest;
import com.yyh.agent.dto.AgentResponse;
import com.yyh.agent.service.AgentChatService;
import com.yyh.agent.service.AgentStreamSink;
import com.yyh.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Slf4j
@Tag(name = "Agent智能路由")
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentChatService agentChatService;

    @Qualifier("agentStreamExecutor")
    private final Executor agentStreamExecutor;

    @Operation(summary = "统一Agent对话入口")
    @PostMapping("/chat")
    public Result<AgentResponse> chat(@RequestBody @Valid AgentRequest request) {
        return Result.success(agentChatService.chat(request));
    }

    /**
     * Agent 流式对话（SSE）：faq/policy 逐 token（复用 knowledge /ask/stream），其它意图整段单发（降级）。
     * 工作下沉到 agentStreamExecutor（DelegatingSecurityContextExecutor 透传身份）。事件：start / meta / token / done / error。
     */
    @Operation(summary = "Agent流式对话（SSE）")
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody @Valid AgentRequest request) {
        SseEmitter emitter = new SseEmitter(180_000L);
        try {
            agentStreamExecutor.execute(() -> {
                try {
                    send(emitter, "start", Map.of("sessionId",
                            request.getSessionId() == null ? -1L : request.getSessionId()));
                    AgentResponse response = agentChatService.chatStream(request, new AgentStreamSink() {
                        @Override
                        public void meta(Map<String, Object> meta) {
                            send(emitter, "meta", meta);
                        }

                        @Override
                        public void token(String text) {
                            send(emitter, "token", Map.of("text", text));
                        }
                    });
                    send(emitter, "done", doneData(response));
                    emitter.complete();
                } catch (Exception ex) {
                    log.warn("Agent流式处理失败: sessionId={}, message={}", request.getSessionId(), ex.getMessage());
                    try {
                        emitter.send(SseEmitter.event().name("error")
                                .data(Map.of("message", ex.getMessage() == null ? "Agent流式处理失败" : ex.getMessage()),
                                        MediaType.APPLICATION_JSON));
                    } catch (IOException ignored) {
                    }
                    emitter.completeWithError(ex);
                }
            });
        } catch (RejectedExecutionException rejected) {
            log.warn("Agent流式被拒绝(线程池满): sessionId={}", request.getSessionId());
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data(Map.of("message", "服务繁忙，请稍后重试"), MediaType.APPLICATION_JSON));
            } catch (IOException ignored) {
            }
            emitter.complete();
        }
        return emitter;
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException ex) {
            throw new IllegalStateException("Agent SSE写出失败: " + ex.getMessage(), ex);
        }
    }

    private static Map<String, Object> doneData(AgentResponse response) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "completed");
        if (response != null) {
            data.put("answer", response.getAnswer());
            data.put("intent", response.getIntent());
            data.put("kbId", response.getKbId());
            data.put("tokenUsed", response.getTokenUsed());
            data.put("durationMs", response.getDurationMs());
        }
        return data;
    }
}
