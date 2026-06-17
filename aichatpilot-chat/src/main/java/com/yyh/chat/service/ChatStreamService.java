package com.yyh.chat.service;

import com.yyh.chat.config.ChatProperties;
import com.yyh.chat.dto.ChatReplyVO;
import com.yyh.chat.dto.ChatSendMessageRequest;
import com.yyh.chat.media.AsrService;
import com.yyh.chat.media.VlmService;
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
import java.util.function.Function;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatStreamService {

    private final ChatService chatService;
    private final ChatProperties chatProperties;
    private final AsrService asrService;
    private final VlmService vlmService;
    @Qualifier("chatTaskExecutor")
    private final Executor executor;

    /**
     * 文本流式发送：请求体已含 content，直接产出回复。
     */
    public SseEmitter streamMessage(Long sessionId, ChatSendMessageRequest request) {
        return runStream(sessionId, emitter -> request);
    }

    /**
     * 语音流式发送：在 SSE 任务内先转写音频（失败走 {@code error} 事件，不会变成 JSON Result 破坏前端流式解析），
     * 转写成功后下发 {@code recognized} 事件（让前端把用户气泡填为转写文本），再以 content=转写文本复用文本流式链路。
     * 转写在 {@code chatTaskExecutor} 线程内进行：占用受 B7 有界线程池保护，宁可拒绝也不无限排队。
     */
    public SseEmitter streamAudioMessage(Long sessionId, byte[] audio, String filename,
                                         String contentType, Integer topK) {
        return runStream(sessionId, emitter -> {
            String text = asrService.transcribe(audio, filename, contentType);
            trySend(emitter, "recognized", Map.of("text", text));
            ChatSendMessageRequest request = new ChatSendMessageRequest();
            request.setContent(text);
            request.setTopK(topK);
            return request;
        });
    }

    /**
     * 图像流式发送：在 SSE 任务内先做 VLM 识别（OCR+简述）并拼出 content（失败走 error 事件），
     * 下发 recognized 事件后以该 content 复用文本流式链路。
     */
    public SseEmitter streamImageMessage(Long sessionId, byte[] image, String filename,
                                         String contentType, String caption, Integer topK) {
        return runStream(sessionId, emitter -> {
            String content = vlmService.recognizeToContent(image, contentType, caption);
            trySend(emitter, "recognized", Map.of("text", content));
            ChatSendMessageRequest request = new ChatSendMessageRequest();
            request.setContent(content);
            request.setTopK(topK);
            return request;
        });
    }

    /**
     * 流式编排骨架（文本/语音/图像共用）：start → 解析请求（媒体在此转写并发 recognized）→ 产出回复 → done。
     * emitter/executor/{@code trySend}/B7 拒绝处理全在此一处，避免各模态重复实现而出错。
     */
    private SseEmitter runStream(Long sessionId,
                                 Function<SseEmitter, ChatSendMessageRequest> requestResolver) {
        SseEmitter emitter = new SseEmitter(chatProperties.getSse().getTimeoutMs());
        boolean trueStreaming = chatProperties.getSse().isTrueStreaming();
        try {
            executor.execute(() -> {
                try {
                    trySend(emitter, "start", Map.of("sessionId", sessionId));
                    // 媒体输入：转写/识别在此发生（可能抛业务异常 → 下方 catch 转 error 事件）；文本输入直接拿到请求。
                    ChatSendMessageRequest request = requestResolver.apply(emitter);
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
