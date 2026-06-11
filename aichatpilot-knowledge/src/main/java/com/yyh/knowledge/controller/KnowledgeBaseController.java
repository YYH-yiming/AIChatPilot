package com.yyh.knowledge.controller;

import com.yyh.common.result.Result;
import com.yyh.knowledge.dto.KnowledgeAskRequest;
import com.yyh.knowledge.dto.KnowledgeAskResponse;
import com.yyh.knowledge.dto.KnowledgeBaseCreateRequest;
import com.yyh.knowledge.dto.KnowledgeSearchHitVO;
import com.yyh.knowledge.dto.KnowledgeSearchRequest;
import com.yyh.knowledge.dto.KnowledgeBaseVO;
import com.yyh.knowledge.service.KnowledgeBaseService;
import com.yyh.knowledge.service.RagStreamSink;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Slf4j
@Tag(name = "知识库管理")
@RestController
@RequestMapping("/api/knowledge/bases")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    @Qualifier("knowledgeStreamExecutor")
    private final Executor knowledgeStreamExecutor;

    @Operation(summary = "创建知识库")
    @PostMapping
    public Result<KnowledgeBaseVO> create(@RequestBody @Valid KnowledgeBaseCreateRequest request) {
        return Result.success(knowledgeBaseService.create(request));
    }

    @Operation(summary = "查询当前租户知识库列表")
    @GetMapping
    public Result<List<KnowledgeBaseVO>> list() {
        return Result.success(knowledgeBaseService.listCurrentTenant());
    }

    @Operation(summary = "查询知识库详情")
    @GetMapping("/{id}")
    public Result<KnowledgeBaseVO> get(@PathVariable Long id) {
        return Result.success(knowledgeBaseService.getById(id));
    }

    @Operation(summary = "更新知识库")
    @PutMapping("/{id}")
    public Result<KnowledgeBaseVO> update(@PathVariable Long id,
                                          @RequestBody @Valid KnowledgeBaseCreateRequest request) {
        return Result.success(knowledgeBaseService.update(id, request));
    }

    @Operation(summary = "删除知识库")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        knowledgeBaseService.delete(id);
        return Result.success();
    }

    @Operation(summary = "检索知识库切片")
    @PostMapping("/{id}/search")
    public Result<List<KnowledgeSearchHitVO>> search(@PathVariable Long id,
                                                     @RequestBody @Valid KnowledgeSearchRequest request) {
        return Result.success(knowledgeBaseService.search(id, request));
    }

    @Operation(summary = "知识库问答")
    @PostMapping("/{id}/ask")
    public Result<KnowledgeAskResponse> ask(@PathVariable Long id,
                                            @RequestBody @Valid KnowledgeAskRequest request) {
        return Result.success(knowledgeBaseService.ask(id, request));
    }

    @Operation(summary = "知识库流式问答（SSE，逐 token 回吐）")
    @PostMapping(value = "/{id}/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@PathVariable Long id,
                                @RequestBody @Valid KnowledgeAskRequest request) {
        SseEmitter emitter = new SseEmitter(180_000L);
        try {
            knowledgeStreamExecutor.execute(() -> {
                try {
                    knowledgeBaseService.askStream(id, request, new RagStreamSink() {
                        @Override
                        public void meta(KnowledgeAskResponse meta) {
                            send(emitter, "meta", meta);
                        }

                        @Override
                        public void token(String delta) {
                            send(emitter, "token", Map.of("text", delta));
                        }

                        @Override
                        public void done(String fullAnswer, int tokenUsed) {
                            send(emitter, "done", Map.of("status", "completed",
                                    "answer", fullAnswer, "tokenUsed", tokenUsed));
                        }
                    });
                    emitter.complete();
                } catch (Exception ex) {
                    log.warn("知识库流式问答失败: kbId={}, message={}", id, ex.getMessage());
                    try {
                        emitter.send(SseEmitter.event().name("error")
                                .data(Map.of("message", ex.getMessage() == null ? "流式问答失败" : ex.getMessage()),
                                        MediaType.APPLICATION_JSON));
                    } catch (IOException ignored) {
                        // 客户端可能已断开，忽略
                    }
                    emitter.completeWithError(ex);
                }
            });
        } catch (RejectedExecutionException rejected) {
            // B7：流式线程池+队列已满，回吐 error 事件而非抛 500，避免无限堆积。
            log.warn("知识库流式问答被拒绝(线程池满): kbId={}", id);
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data(Map.of("message", "服务繁忙，请稍后重试"), MediaType.APPLICATION_JSON));
            } catch (IOException ignored) {
            }
            emitter.complete();
        }
        return emitter;
    }

    /** 发送一个 SSE 事件；写出失败（如客户端断开）抛出以中断后续生成。 */
    private static void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException ex) {
            throw new IllegalStateException("SSE写出失败: " + ex.getMessage(), ex);
        }
    }
}
