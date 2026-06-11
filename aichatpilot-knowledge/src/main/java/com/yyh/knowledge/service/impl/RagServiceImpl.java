package com.yyh.knowledge.service.impl;

import com.yyh.knowledge.cache.FaqCacheService;
import com.yyh.knowledge.dto.KnowledgeAskRequest;
import com.yyh.knowledge.dto.KnowledgeAskResponse;
import com.yyh.knowledge.dto.KnowledgeSearchHitVO;
import com.yyh.knowledge.llm.LlmService;
import com.yyh.knowledge.search.RetrievalService;
import com.yyh.knowledge.search.RetrievalOptions;
import com.yyh.knowledge.service.RagService;
import com.yyh.knowledge.service.RagStreamSink;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagServiceImpl implements RagService {

    private static final String DEFAULT_SYSTEM_PROMPT = """
            你是一个专业的客服助手。请根据提供的参考资料回答用户问题。
            如果参考资料中没有相关信息，请明确回复“我没有在知识库中找到相关信息”。
            不要编造参考资料中没有出现的事实，不要脱离资料自行发挥。
            回答尽量准确、简洁、结构清晰。
            """;

    private final RetrievalService retrievalService;
    private final LlmService llmService;
    private final FaqCacheService faqCacheService;

    @Value("${knowledge.rag.top-k:5}")
    private int defaultTopK;

    @Value("${knowledge.rag.empty-answer:我没有在知识库中找到相关信息}")
    private String emptyAnswer;

    @Value("${knowledge.rag.system-prompt:}")
    private String systemPrompt;

    @Override
    public KnowledgeAskResponse ask(Long kbId, KnowledgeAskRequest request) {
        int topK = request.getTopK() == null ? defaultTopK : request.getTopK();
        // 带显式检索覆盖项（评测切 arm）时绕过 FAQ 缓存，避免不同 arm 经缓存互相污染
        boolean bypassCache = request.getDenseEnabled() != null || request.getSparseEnabled() != null
                || request.getRerankEnabled() != null || request.getRecallTopN() != null;
        KnowledgeAskResponse cached = bypassCache ? null : faqCacheService.get(kbId, request.getQuery(), topK);
        if (cached != null) {
            log.info("[PERF] stage=rag kbId={} topK={} cacheHit=true retrievalMs=0 llmMs=0", kbId, topK);
            return cached;
        }
        long retrievalStart = System.nanoTime();
        List<KnowledgeSearchHitVO> references = retrievalService.search(request.getQuery(), kbId, topK,
                new RetrievalOptions(request.getDenseEnabled(), request.getSparseEnabled(),
                        request.getRerankEnabled(), request.getRecallTopN()));
        long retrievalMs = elapsedMs(retrievalStart);

        KnowledgeAskResponse response = new KnowledgeAskResponse();
        response.setKbId(kbId);
        response.setQuery(request.getQuery());
        response.setTopK(topK);
        response.setReferences(references);
        response.setReferenceCount(references.size());
        response.setGrounded(!references.isEmpty());
        response.setModel(llmService.currentModel());

        if (references.isEmpty()) {
            response.setAnswer(emptyAnswer);
            if (!bypassCache) {
                faqCacheService.put(response);
            }
            log.info("[PERF] stage=rag kbId={} topK={} cacheHit=false grounded=false refs=0 retrievalMs={} llmMs=-1",
                    kbId, topK, retrievalMs);
            return response;
        }

        long llmStart = System.nanoTime();
        String answer = llmService.chat(resolveSystemPrompt(), buildUserPrompt(request.getQuery(), references));
        long llmMs = elapsedMs(llmStart);
        response.setAnswer(StringUtils.hasText(answer) ? answer.trim() : emptyAnswer);
        if (!bypassCache) {
            faqCacheService.put(response);
        }
        log.info("[PERF] stage=rag kbId={} topK={} cacheHit=false grounded=true refs={} retrievalMs={} llmMs={}",
                kbId, topK, references.size(), retrievalMs, llmMs);
        return response;
    }

    @Override
    public void askStream(Long kbId, KnowledgeAskRequest request, RagStreamSink sink) {
        int topK = request.getTopK() == null ? defaultTopK : request.getTopK();
        // 流式路径暂不走 FAQ 缓存（基线期缓存关闭；语义缓存为独立 P1 项）。
        long retrievalStart = System.nanoTime();
        List<KnowledgeSearchHitVO> references = retrievalService.search(request.getQuery(), kbId, topK,
                new RetrievalOptions(request.getDenseEnabled(), request.getSparseEnabled(),
                        request.getRerankEnabled(), request.getRecallTopN()));
        long retrievalMs = elapsedMs(retrievalStart);

        KnowledgeAskResponse meta = new KnowledgeAskResponse();
        meta.setKbId(kbId);
        meta.setQuery(request.getQuery());
        meta.setTopK(topK);
        meta.setReferences(references);
        meta.setReferenceCount(references.size());
        meta.setGrounded(!references.isEmpty());
        meta.setModel(llmService.currentModel());
        sink.meta(meta);

        if (references.isEmpty()) {
            sink.token(emptyAnswer);
            sink.done(emptyAnswer, 0);
            log.info("[PERF] stage=rag-stream kbId={} topK={} grounded=false refs=0 retrievalMs={} llmMs=-1",
                    kbId, topK, retrievalMs);
            return;
        }

        long llmStart = System.nanoTime();
        LlmService.StreamResult result = llmService.chatStream(resolveSystemPrompt(),
                buildUserPrompt(request.getQuery(), references), sink::token);
        long llmMs = elapsedMs(llmStart);
        String answer = StringUtils.hasText(result.fullText()) ? result.fullText().trim() : emptyAnswer;
        sink.done(answer, result.totalTokens());
        log.info("[PERF] stage=rag-stream kbId={} topK={} grounded=true refs={} retrievalMs={} llmMs={}",
                kbId, topK, references.size(), retrievalMs, llmMs);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private String resolveSystemPrompt() {
        return StringUtils.hasText(systemPrompt) ? systemPrompt : DEFAULT_SYSTEM_PROMPT;
    }

    private String buildUserPrompt(String query, List<KnowledgeSearchHitVO> references) {
        StringBuilder builder = new StringBuilder();
        builder.append("参考资料：\n");
        for (int i = 0; i < references.size(); i++) {
            KnowledgeSearchHitVO reference = references.get(i);
            builder.append("---\n");
            builder.append("资料").append(i + 1)
                    .append("（chunkId=").append(reference.getChunkId())
                    .append(", docId=").append(reference.getDocId())
                    .append(", source=").append(reference.getSource())
                    .append("）:\n");
            builder.append(reference.getContent()).append('\n');
        }
        builder.append("---\n");
        builder.append("用户问题：").append(query).append('\n');
        builder.append("请仅基于上述资料作答；如果资料不足，请直接说明未找到相关信息。");
        return builder.toString();
    }
}
