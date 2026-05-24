package com.yyh.chat.controller;

import com.yyh.chat.dto.ChatMessageVO;
import com.yyh.chat.dto.ChatReplyVO;
import com.yyh.chat.dto.ChatSendMessageRequest;
import com.yyh.chat.dto.ChatSessionCreateRequest;
import com.yyh.chat.dto.ChatSessionDetailVO;
import com.yyh.chat.dto.ChatSessionVO;
import com.yyh.chat.service.ChatService;
import com.yyh.chat.service.ChatStreamService;
import com.yyh.common.dto.PageResult;
import com.yyh.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Tag(name = "Chat会话服务")
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final ChatStreamService chatStreamService;

    @Operation(summary = "创建会话")
    @PostMapping("/sessions")
    public Result<ChatSessionVO> createSession(@RequestBody @Valid ChatSessionCreateRequest request) {
        return Result.success(chatService.createSession(request));
    }

    @Operation(summary = "分页查询当前用户会话")
    @GetMapping("/sessions")
    public Result<PageResult<ChatSessionVO>> pageSessions(@RequestParam(defaultValue = "1") int pageNum,
                                                          @RequestParam(defaultValue = "10") int pageSize) {
        return Result.success(chatService.pageSessions(pageNum, pageSize));
    }

    @Operation(summary = "查询会话详情与最近消息")
    @GetMapping("/sessions/{sessionId}")
    public Result<ChatSessionDetailVO> sessionDetail(@PathVariable Long sessionId,
                                                     @RequestParam(defaultValue = "20") Integer messageLimit) {
        return Result.success(chatService.getSessionDetail(sessionId, messageLimit));
    }

    @Operation(summary = "分页查询会话消息")
    @GetMapping("/sessions/{sessionId}/messages")
    public Result<PageResult<ChatMessageVO>> pageMessages(@PathVariable Long sessionId,
                                                          @RequestParam(defaultValue = "1") int pageNum,
                                                          @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(chatService.pageMessages(sessionId, pageNum, pageSize));
    }

    @Operation(summary = "发送消息并同步返回答案")
    @PostMapping("/sessions/{sessionId}/messages")
    public Result<ChatReplyVO> sendMessage(@PathVariable Long sessionId,
                                           @RequestBody @Valid ChatSendMessageRequest request) {
        return Result.success(chatService.sendMessage(sessionId, request));
    }

    @Operation(summary = "发送消息并通过SSE返回结果")
    @PostMapping(value = "/sessions/{sessionId}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMessage(@PathVariable Long sessionId,
                                    @RequestBody @Valid ChatSendMessageRequest request) {
        return chatStreamService.streamMessage(sessionId, request);
    }

    @Operation(summary = "关闭会话")
    @PostMapping("/sessions/{sessionId}/close")
    public Result<Void> closeSession(@PathVariable Long sessionId) {
        chatService.closeSession(sessionId);
        return Result.success();
    }
}
