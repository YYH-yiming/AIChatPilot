package com.yyh.agent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyh.agent.config.AgentProperties;
import com.yyh.agent.dto.AgentRequest;
import com.yyh.agent.dto.IntentResult;
import com.yyh.agent.llm.AgentLlmResult;
import com.yyh.agent.llm.AgentLlmService;
import com.yyh.agent.memory.ShortTermMemory;
import com.yyh.agent.support.SecurityUtils;
import com.yyh.agent.trace.AgentTraceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class RouterAgent {

    private final AgentLlmService agentLlmService;
    private final AgentProperties agentProperties;
    private final ShortTermMemory shortTermMemory;
    private final AgentTraceService agentTraceService;
    private final ObjectMapper objectMapper;

    public IntentResult route(AgentRequest request) {
        long start = System.currentTimeMillis();
        Integer tokenUsed = 0;
        String output = null;
        try {
            AgentLlmResult llmResult = tryClassifyByLlm(request);
            tokenUsed = llmResult.getTokenUsed();
            IntentResult result = parseIntentResult(llmResult.getContent());
            output = llmResult.getContent();
            if (isValid(result)) {
                recordTrace(request, result.getIntent(), tokenUsed, "success", null, output, start);
                return result;
            }
        } catch (Exception ignored) {
        }

        IntentResult fallback = classifyByRule(request.getQuery());
        output = toJsonSafely(fallback);
        recordTrace(request, fallback.getIntent(), tokenUsed, "success", null, output, start);
        return fallback;
    }

    private AgentLlmResult tryClassifyByLlm(AgentRequest request) {
        String conversationContext = shortTermMemory.buildConversationContext(request.getSessionId(), SecurityUtils.currentUserId());
        String systemPrompt = """
                你是客服路由Agent。请把用户问题分类为以下五种之一：
                - faq：产品功能、使用方法、常见说明
                - policy：退货政策、发票规则、保修条款、隐私协议、服务规则
                - order：订单状态、物流、发货、签收
                - ticket：退款、退货、换货、投诉、售后处理
                - escalation：找人工、转人工、升级处理

                只输出JSON，不要输出Markdown代码块，格式如下：
                {"intent":"faq","confidence":0.92,"reason":"一句简短原因"}
                """;
        StringBuilder userPrompt = new StringBuilder();
        if (StringUtils.hasText(conversationContext)) {
            userPrompt.append("最近对话上下文：\n").append(conversationContext).append("\n\n");
        }
        userPrompt.append("用户当前问题：").append(request.getQuery());
        return agentLlmService.chat(systemPrompt, userPrompt.toString());
    }

    private IntentResult parseIntentResult(String raw) throws Exception {
        String normalized = extractJson(raw);
        JsonNode node = objectMapper.readTree(normalized);
        IntentResult result = new IntentResult();
        result.setIntent(normalizeIntent(node.path("intent").asText(agentProperties.getRouter().getFallbackIntent())));
        result.setConfidence(node.path("confidence").asDouble(0.0));
        result.setReason(node.path("reason").asText(""));
        return result;
    }

    private boolean isValid(IntentResult result) {
        return result != null
                && StringUtils.hasText(result.getIntent())
                && result.getConfidence() != null
                && result.getConfidence() >= agentProperties.getRouter().getConfidenceThreshold();
    }

    private IntentResult classifyByRule(String query) {
        String normalizedQuery = query == null ? "" : query.toLowerCase();
        IntentResult result = new IntentResult();
        result.setConfidence(0.78);

        if (containsAny(normalizedQuery, "人工", "转人工", "人工客服", "专员")) {
            result.setIntent("escalation");
            result.setReason("命中人工转接关键词");
            return result;
        }
        if (containsAny(normalizedQuery, "订单", "物流", "快递", "发货", "配送", "签收", "单号")) {
            result.setIntent("order");
            result.setReason("命中订单物流关键词");
            return result;
        }
        if (containsAny(normalizedQuery, "退货", "退款", "换货", "投诉", "工单", "售后", "质量问题")) {
            result.setIntent("ticket");
            result.setReason("命中售后工单关键词");
            return result;
        }
        if (containsAny(normalizedQuery, "政策", "规则", "条款", "协议", "发票", "保修", "隐私")) {
            result.setIntent("policy");
            result.setReason("命中规则政策关键词");
            return result;
        }
        result.setIntent(agentProperties.getRouter().getFallbackIntent());
        result.setReason("默认归类为FAQ");
        return result;
    }

    private boolean containsAny(String query, String... keywords) {
        for (String keyword : keywords) {
            if (query.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String extractJson(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "{\"intent\":\"faq\",\"confidence\":0.0,\"reason\":\"empty\"}";
        }
        String cleaned = raw.replace("```json", "").replace("```", "").trim();
        int firstBrace = cleaned.indexOf('{');
        int lastBrace = cleaned.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return cleaned.substring(firstBrace, lastBrace + 1);
        }
        return cleaned;
    }

    private String normalizeIntent(String intent) {
        if (!StringUtils.hasText(intent)) {
            return agentProperties.getRouter().getFallbackIntent();
        }
        String value = intent.trim().toLowerCase();
        if (value.contains("policy") || value.contains("规则") || value.contains("政策")) {
            return "policy";
        }
        if (value.contains("order") || value.contains("订单") || value.contains("物流")) {
            return "order";
        }
        if (value.contains("ticket") || value.contains("工单") || value.contains("售后") || value.contains("refund")) {
            return "ticket";
        }
        if (value.contains("escalation") || value.contains("人工") || value.contains("human")) {
            return "escalation";
        }
        return "faq";
    }

    private void recordTrace(AgentRequest request,
                             String intent,
                             Integer tokenUsed,
                             String status,
                             String errorMsg,
                             String output,
                             long start) {
        agentTraceService.record(AgentTraceService.TraceRecord.builder()
                .tenantId(request.getTenantId())
                .sessionId(request.getSessionId())
                .agentName("router")
                .intent(intent)
                .inputText(request.getQuery())
                .outputText(output)
                .toolsCalled(null)
                .toolResults(null)
                .tokenUsed(tokenUsed)
                .durationMs((int) (System.currentTimeMillis() - start))
                .status(status)
                .errorMsg(errorMsg)
                .build());
    }

    private String toJsonSafely(IntentResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception ex) {
            return "{\"intent\":\"faq\",\"confidence\":0.0,\"reason\":\"serialize_failed\"}";
        }
    }
}
