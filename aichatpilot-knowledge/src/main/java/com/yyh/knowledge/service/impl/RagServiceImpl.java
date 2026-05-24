package com.yyh.knowledge.service.impl;

import com.yyh.knowledge.cache.FaqCacheService;
import com.yyh.knowledge.dto.KnowledgeAskRequest;
import com.yyh.knowledge.dto.KnowledgeAskResponse;
import com.yyh.knowledge.dto.KnowledgeSearchHitVO;
import com.yyh.knowledge.llm.LlmService;
import com.yyh.knowledge.search.RetrievalService;
import com.yyh.knowledge.service.RagService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

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
        KnowledgeAskResponse cached = faqCacheService.get(kbId, request.getQuery(), topK);
        if (cached != null) {
            return cached;
        }
        List<KnowledgeSearchHitVO> references = retrievalService.search(request.getQuery(), kbId, topK);

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
            faqCacheService.put(response);
            return response;
        }

        String answer = llmService.chat(resolveSystemPrompt(), buildUserPrompt(request.getQuery(), references));
        response.setAnswer(StringUtils.hasText(answer) ? answer.trim() : emptyAnswer);
        faqCacheService.put(response);
        return response;
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
