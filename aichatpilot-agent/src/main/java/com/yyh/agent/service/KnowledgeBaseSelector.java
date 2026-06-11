package com.yyh.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyh.agent.client.KnowledgeServiceClient;
import com.yyh.agent.client.dto.KnowledgeAskClientResponse;
import com.yyh.agent.client.dto.KnowledgeBaseClientResponse;
import com.yyh.agent.client.dto.KnowledgeSearchHitClientResponse;
import com.yyh.agent.dto.AgentRequest;
import com.yyh.agent.llm.AgentLlmResult;
import com.yyh.agent.llm.AgentLlmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseSelector {

    private final KnowledgeServiceClient knowledgeServiceClient;
    private final AgentLlmService agentLlmService;
    private final ObjectMapper objectMapper;

    public SelectionResult askAcrossKnowledgeBases(AgentRequest request,
                                                   String intent,
                                                   Long defaultKbId,
                                                   Integer topK) {
        if (request.getKbId() != null && request.getKbId() > 0) {
            KnowledgeAskClientResponse response = knowledgeServiceClient.ask(request.getKbId(), request.getQuery(), topK);
            return new SelectionResult(request.getKbId(), response, "request-kbid");
        }

        List<KnowledgeBaseClientResponse> knowledgeBases = safeListKnowledgeBases();
        if (knowledgeBases.isEmpty()) {
            KnowledgeAskClientResponse response = knowledgeServiceClient.ask(defaultKbId, request.getQuery(), topK);
            return new SelectionResult(defaultKbId, response, "default-kbid");
        }

        Optional<KnowledgeBaseClientResponse> selectedByLlm = selectByLlm(intent, request.getQuery(), knowledgeBases);
        if (selectedByLlm.isPresent()) {
            KnowledgeBaseClientResponse selected = selectedByLlm.get();
            KnowledgeAskClientResponse response = knowledgeServiceClient.ask(selected.getId(), request.getQuery(), topK);
            return new SelectionResult(selected.getId(), response, "llm-selected");
        }

        Optional<KnowledgeBaseClientResponse> selectedBySearch = selectBySearch(request.getQuery(), topK, knowledgeBases);
        if (selectedBySearch.isPresent()) {
            KnowledgeBaseClientResponse selected = selectedBySearch.get();
            KnowledgeAskClientResponse response = knowledgeServiceClient.ask(selected.getId(), request.getQuery(), topK);
            return new SelectionResult(selected.getId(), response, "search-all-selected");
        }

        KnowledgeBaseClientResponse fallback = resolveFallbackKnowledgeBase(knowledgeBases, defaultKbId);
        KnowledgeAskClientResponse response = knowledgeServiceClient.ask(fallback.getId(), request.getQuery(), topK);
        return new SelectionResult(fallback.getId(), response, "fallback-kbid");
    }

    /**
     * 只选库不发问：抽出 {@link #askAcrossKnowledgeBases} 的选库逻辑（复用同样的 LLM 选库 / 全库检索 / 兜底），
     * 返回选中的 kbId，供流式路径（先选库、再走 knowledge /ask/stream）使用。
     */
    public Long selectKbId(AgentRequest request, String intent, Long defaultKbId, Integer topK) {
        if (request.getKbId() != null && request.getKbId() > 0) {
            return request.getKbId();
        }
        List<KnowledgeBaseClientResponse> knowledgeBases = safeListKnowledgeBases();
        if (knowledgeBases.isEmpty()) {
            return defaultKbId;
        }
        Optional<KnowledgeBaseClientResponse> byLlm = selectByLlm(intent, request.getQuery(), knowledgeBases);
        if (byLlm.isPresent()) {
            return byLlm.get().getId();
        }
        Optional<KnowledgeBaseClientResponse> bySearch = selectBySearch(request.getQuery(), topK, knowledgeBases);
        if (bySearch.isPresent()) {
            return bySearch.get().getId();
        }
        return resolveFallbackKnowledgeBase(knowledgeBases, defaultKbId).getId();
    }

    private List<KnowledgeBaseClientResponse> safeListKnowledgeBases() {
        try {
            List<KnowledgeBaseClientResponse> list = knowledgeServiceClient.listKnowledgeBases();
            return list == null ? List.of() : list.stream()
                    .filter(item -> item.getStatus() == null || item.getStatus() == 1)
                    .toList();
        } catch (Exception ex) {
            log.warn("查询知识库列表失败，将退回默认知识库: {}", ex.getMessage());
            return List.of();
        }
    }

    private Optional<KnowledgeBaseClientResponse> selectByLlm(String intent,
                                                              String query,
                                                              List<KnowledgeBaseClientResponse> knowledgeBases) {
        try {
            AgentLlmResult llmResult = agentLlmService.chat(buildSystemPrompt(), buildUserPrompt(intent, query, knowledgeBases));
            Decision decision = parseDecision(llmResult.getContent(), knowledgeBases);
            if (decision == null || !"single".equals(decision.mode())) {
                return Optional.empty();
            }
            return knowledgeBases.stream()
                    .filter(item -> item.getId().equals(decision.kbId()))
                    .findFirst();
        } catch (Exception ex) {
            log.warn("LLM选库失败，将退回全库检索兜底: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private Optional<KnowledgeBaseClientResponse> selectBySearch(String query,
                                                                 Integer topK,
                                                                 List<KnowledgeBaseClientResponse> knowledgeBases) {
        return knowledgeBases.stream()
                .map(base -> toSearchCandidate(base, query, topK))
                .filter(candidate -> candidate != null && candidate.hitCount() > 0)
                .max(Comparator.comparingDouble(SearchCandidate::rankScore)
                        .thenComparingInt(SearchCandidate::hitCount))
                .map(SearchCandidate::base);
    }

    private SearchCandidate toSearchCandidate(KnowledgeBaseClientResponse base, String query, Integer topK) {
        try {
            List<KnowledgeSearchHitClientResponse> hits = knowledgeServiceClient.search(base.getId(), query, topK);
            if (hits == null || hits.isEmpty()) {
                return new SearchCandidate(base, 0.0, 0);
            }
            KnowledgeSearchHitClientResponse first = hits.get(0);
            double bestScore = first.getScore() == null ? 0.0 : first.getScore();
            if (first.getSource() != null && !"db".equalsIgnoreCase(first.getSource())) {
                bestScore += 0.1D;
            }
            bestScore += hits.size() * 0.001D;
            return new SearchCandidate(base, bestScore, hits.size());
        } catch (Exception ex) {
            log.warn("全库检索候选失败: kbId={}, message={}", base.getId(), ex.getMessage());
            return null;
        }
    }

    private KnowledgeBaseClientResponse resolveFallbackKnowledgeBase(List<KnowledgeBaseClientResponse> knowledgeBases, Long defaultKbId) {
        if (defaultKbId != null) {
            Optional<KnowledgeBaseClientResponse> match = knowledgeBases.stream()
                    .filter(item -> item.getId().equals(defaultKbId))
                    .findFirst();
            if (match.isPresent()) {
                return match.get();
            }
        }
        return knowledgeBases.get(0);
    }

    private Decision parseDecision(String raw, List<KnowledgeBaseClientResponse> knowledgeBases) throws Exception {
        String normalized = extractJson(raw);
        JsonNode node = objectMapper.readTree(normalized);
        String mode = node.path("mode").asText("all").trim().toLowerCase(Locale.ROOT);
        if (!"single".equals(mode)) {
            return new Decision("all", null, null);
        }

        Long kbId = node.path("kbId").isNumber() ? node.path("kbId").asLong() : null;
        String kbName = node.path("kbName").asText("");
        if (kbId != null) {
            return new Decision("single", kbId, kbName);
        }

        if (StringUtils.hasText(kbName)) {
            Optional<KnowledgeBaseClientResponse> matched = knowledgeBases.stream()
                    .filter(item -> normalize(item.getName()).equals(normalize(kbName)))
                    .findFirst();
            if (matched.isPresent()) {
                return new Decision("single", matched.get().getId(), matched.get().getName());
            }
        }
        return new Decision("all", null, kbName);
    }

    private String buildSystemPrompt() {
        return """
                你是知识库选库助手。
                你的任务是根据用户问题，从当前租户的知识库列表中判断应该优先查询哪个知识库。

                你必须只输出 JSON，不要输出 Markdown，不要解释。

                输出只有两种格式：
                1. 选定单个知识库：
                {"mode":"single","kbId":12,"kbName":"客户服务政策库","reason":"问题询问退款规则"}
                2. 无法明确判断，需要全库检索：
                {"mode":"all","reason":"问题语义过泛或多个知识库都可能相关"}

                判断原则：
                - 如果用户问题明显落在某一个知识库主题，输出 single
                - 如果问题跨多个主题、表述过短、上下文不足，输出 all
                - 不要编造不存在的 kbId
                - 优先结合知识库名称和描述语义判断

                Few-shot 示例 1：
                用户问题：退款多久到账？
                知识库列表：客户服务政策库、产品价格与合同库、公司介绍与销售口径库
                输出：{"mode":"single","kbId":1,"kbName":"客户服务政策库","reason":"问题属于退款与到账时效"}

                Few-shot 示例 2：
                用户问题：标准版多少钱？
                知识库列表：客户服务政策库、产品价格与合同库、公司介绍与销售口径库
                输出：{"mode":"single","kbId":2,"kbName":"产品价格与合同库","reason":"问题属于套餐价格"}

                Few-shot 示例 3：
                用户问题：这个怎么处理？
                知识库列表：客户服务政策库、产品价格与合同库、客服运营与工单流程库
                输出：{"mode":"all","reason":"问题过于泛化，无法仅凭当前内容锁定单个知识库"}
                """;
    }

    private String buildUserPrompt(String intent, String query, List<KnowledgeBaseClientResponse> knowledgeBases) {
        StringBuilder builder = new StringBuilder();
        builder.append("路由意图：").append(intent).append('\n');
        builder.append("用户问题：").append(query).append("\n\n");
        builder.append("当前可用知识库列表：\n");
        for (KnowledgeBaseClientResponse base : knowledgeBases) {
            builder.append("- kbId=").append(base.getId())
                    .append(", name=").append(base.getName());
            if (StringUtils.hasText(base.getDescription())) {
                builder.append(", description=").append(base.getDescription());
            }
            builder.append('\n');
        }
        builder.append("\n请只输出 JSON。");
        return builder.toString();
    }

    private String extractJson(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "{\"mode\":\"all\",\"reason\":\"empty\"}";
        }
        String cleaned = raw.replace("```json", "").replace("```", "").trim();
        int firstBrace = cleaned.indexOf('{');
        int lastBrace = cleaned.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return cleaned.substring(firstBrace, lastBrace + 1);
        }
        return cleaned;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record Decision(String mode, Long kbId, String kbName) {
    }

    private record SearchCandidate(KnowledgeBaseClientResponse base, double rankScore, int hitCount) {
    }

    public record SelectionResult(Long kbId, KnowledgeAskClientResponse response, String strategy) {
    }
}
