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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

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
        boolean trueStreaming = chatProperties.getSse().isTrueStreaming();
        try {
            executor.execute(() -> {
                try {
                    trySend(emitter, "start", Map.of("sessionId", sessionId));
                    if (trueStreaming) {
                        ChatStreamSink sink = new ChatStreamSink() {
                            @Override
                            public void meta(Map<String, Object> meta) {
                                trySend(emitter, "meta", meta);
                            }

                            @Override
                            public void token(String text) {
                                trySend(emitter, "token", Map.of("text", text));
                            }
                        };
                        ChatReplyVO reply = chatService.sendMessageStreaming(sessionId, request, sink);
                        trySend(emitter, "done", doneData(reply));
                    } else {
                        ChatReplyVO reply = chatService.sendMessage(sessionId, request);
                        trySend(emitter, "reply", reply);
                        trySend(emitter, "done", doneData(reply));
                    }
                    emitter.complete();
                } catch (Exception ex) {
                    log.warn("SSE聊天处理失败: sessionId={}, trueStreaming={}, message={}",
                            sessionId, trueStreaming, ex.getMessage());
                    try {
                        emitter.send(SseEmitter.event().name("error").data(Map.of("message",
                                ex.getMessage() == null ? "聊天流式处理失败" : ex.getMessage())));
                    } catch (IOException ignored) {
                    }
                    emitter.completeWithError(ex);
                }
            });
        } catch (RejectedExecutionException rejected) {
            // B7：线程池+队列已满。有界拒绝优于无限排队/OOM——回吐 error 事件并正常收尾，不抛 500。
            log.warn("SSE聊天处理被拒绝(线程池满): sessionId={}", sessionId);
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data(Map.of("message", "服务繁忙，请稍后重试")));
            } catch (IOException ignored) {
            }
            emitter.complete();
        }
        return emitter;
    }

    private void trySend(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException ex) {
            // 客户端断开/写失败：抛出中断整个流式处理，由外层 catch 统一收尾。
            throw new IllegalStateException("SSE发送失败: event=" + event, ex);
        }
    }

    /** done 事件载荷：保留旧版 status=completed，并补充三个消息 id 与耗时，便于前端落库/对齐。 */
    private static Map<String, Object> doneData(ChatReplyVO reply) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "completed");
        if (reply != null) {
            data.put("sessionId", reply.getSessionId());
            data.put("userMessageId", reply.getUserMessageId());
            data.put("assistantMessageId", reply.getAssistantMessageId());
            data.put("durationMs", reply.getDurationMs());
        }
        return data;
    }
}
