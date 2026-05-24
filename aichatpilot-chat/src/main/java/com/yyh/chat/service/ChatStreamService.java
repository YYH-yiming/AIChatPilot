package com.yyh.chat.service;

import com.yyh.chat.config.ChatProperties;
import com.yyh.chat.dto.ChatReplyVO;
import com.yyh.chat.dto.ChatSendMessageRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executor;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatStreamService {

    private final ChatService chatService;
    private final ChatProperties chatProperties;
    @Qualifier("chatTaskExecutor")
    private final Executor executor;

    public SseEmitter streamMessage(Long sessionId, ChatSendMessageRequest request) {
        SseEmitter emitter = new SseEmitter(chatProperties.getSse().getTimeoutMs());
        executor.execute(() -> {
            try {
                emitter.send(SseEmitter.event().name("start").data(Map.of("sessionId", sessionId)));
                ChatReplyVO reply = chatService.sendMessage(sessionId, request);
                emitter.send(SseEmitter.event().name("reply").data(reply));
                emitter.send(SseEmitter.event().name("done").data(Map.of("status", "completed")));
                emitter.complete();
            } catch (Exception ex) {
                log.warn("SSE聊天处理失败: sessionId={}, message={}", sessionId, ex.getMessage());
                try {
                    emitter.send(SseEmitter.event().name("error").data(Map.of("message", ex.getMessage())));
                } catch (IOException ignored) {
                }
                emitter.completeWithError(ex);
            }
        });
        return emitter;
    }
}
