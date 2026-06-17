package com.yyh.chat.controller;

import com.yyh.chat.dto.ChatReplyVO;
import com.yyh.chat.dto.ChatSendMessageRequest;
import com.yyh.chat.media.AsrService;
import com.yyh.chat.media.VlmService;
import com.yyh.chat.service.ChatService;
import com.yyh.chat.service.ChatStreamService;
import com.yyh.common.exception.BusinessException;
import com.yyh.common.result.Result;
import com.yyh.common.result.ResultCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * 多模态输入端点（语音/图像）。<b>纯加性</b>：与文本端点 {@link ChatController} 同前缀 {@code /api/chat}，
 * 但不碰其任何方法。统一做法=把媒体转成文本(content)，再复用现有 {@link ChatService}/{@link ChatStreamService} 全链路。
 */
@Tag(name = "Chat多模态输入")
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatMediaController {

    private final AsrService asrService;
    private final VlmService vlmService;
    private final ChatService chatService;
    private final ChatStreamService chatStreamService;

    @Operation(summary = "发送语音消息并同步返回答案（转写→复用知识库/Agent问答）")
    @PostMapping(value = "/sessions/{sessionId}/messages/audio",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<ChatReplyVO> sendAudio(@PathVariable Long sessionId,
                                         @RequestPart("file") MultipartFile file,
                                         @RequestParam(value = "topK", required = false) Integer topK) {
        String transcript = asrService.transcribe(readBytes(file), file.getOriginalFilename(), file.getContentType());
        ChatSendMessageRequest request = new ChatSendMessageRequest();
        request.setContent(transcript);
        request.setTopK(topK);
        ChatReplyVO reply = chatService.sendMessage(sessionId, request);
        reply.setRecognizedText(transcript);
        return Result.success(reply);
    }

    @Operation(summary = "发送语音消息并通过SSE流式返回（先发 recognized=转写文本，再逐 token 答案）")
    @PostMapping(value = "/sessions/{sessionId}/messages/audio/stream",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamAudio(@PathVariable Long sessionId,
                                  @RequestPart("file") MultipartFile file,
                                  @RequestParam(value = "topK", required = false) Integer topK) {
        // 转写放在 SSE 任务内（见 ChatStreamService.streamAudioMessage）：失败走 error 事件，不破坏前端流式解析。
        return chatStreamService.streamAudioMessage(
                sessionId, readBytes(file), file.getOriginalFilename(), file.getContentType(), topK);
    }

    @Operation(summary = "发送图片消息并同步返回答案（VLM识别→拼文字→复用知识库/Agent问答）")
    @PostMapping(value = "/sessions/{sessionId}/messages/image",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<ChatReplyVO> sendImage(@PathVariable Long sessionId,
                                         @RequestPart("file") MultipartFile file,
                                         @RequestParam(value = "text", required = false) String text,
                                         @RequestParam(value = "topK", required = false) Integer topK) {
        String content = vlmService.recognizeToContent(readBytes(file), file.getContentType(), text);
        ChatSendMessageRequest request = new ChatSendMessageRequest();
        request.setContent(content);
        request.setTopK(topK);
        ChatReplyVO reply = chatService.sendMessage(sessionId, request);
        reply.setRecognizedText(content);
        return Result.success(reply);
    }

    @Operation(summary = "发送图片消息并通过SSE流式返回（先发 recognized=识别文本，再逐 token 答案）")
    @PostMapping(value = "/sessions/{sessionId}/messages/image/stream",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamImage(@PathVariable Long sessionId,
                                  @RequestPart("file") MultipartFile file,
                                  @RequestParam(value = "text", required = false) String text,
                                  @RequestParam(value = "topK", required = false) Integer topK) {
        return chatStreamService.streamImageMessage(
                sessionId, readBytes(file), file.getOriginalFilename(), file.getContentType(), text, topK);
    }

    private byte[] readBytes(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "上传的媒体文件不能为空");
        }
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "读取上传文件失败: " + ex.getMessage());
        }
    }
}
