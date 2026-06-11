package com.yyh.agent.service;

import com.yyh.agent.agent.EscalationAgent;
import com.yyh.agent.agent.FaqAgent;
import com.yyh.agent.agent.OrderAgent;
import com.yyh.agent.agent.PolicyAgent;
import com.yyh.agent.agent.RouterAgent;
import com.yyh.agent.agent.TicketAgent;
import com.yyh.agent.dto.AgentRequest;
import com.yyh.agent.dto.AgentResponse;
import com.yyh.agent.client.KnowledgeServiceClient;
import com.yyh.agent.client.dto.KnowledgeSearchHitClientResponse;
import com.yyh.agent.config.AgentProperties;
import com.yyh.agent.dto.IntentResult;
import com.yyh.agent.dto.KnowledgeReference;
import com.yyh.agent.memory.ShortTermMemory;
import com.yyh.agent.support.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AgentChatService {

    private final RouterAgent routerAgent;
    private final FaqAgent faqAgent;
    private final PolicyAgent policyAgent;
    private final OrderAgent orderAgent;
    private final TicketAgent ticketAgent;
    private final EscalationAgent escalationAgent;
    private final ShortTermMemory shortTermMemory;
    private final KnowledgeServiceClient knowledgeServiceClient;
    private final KnowledgeBaseSelector knowledgeBaseSelector;
    private final AgentProperties agentProperties;

    public AgentResponse chat(AgentRequest request) {
        long start = System.currentTimeMillis();
        if (request.getTenantId() == null) {
            request.setTenantId(SecurityUtils.currentTenantId());
        }

        IntentResult intentResult = routerAgent.route(request);
        AgentResponse response = dispatch(intentResult.getIntent(), request);
        response.setIntent(intentResult.getIntent());
        response.setConfidence(intentResult.getConfidence());
        response.setSessionId(request.getSessionId());
        response.setDurationMs(System.currentTimeMillis() - start);

        Long userId = SecurityUtils.currentUserId();
        shortTermMemory.appendUserMessage(request.getSessionId(), userId, request.getQuery());
        shortTermMemory.appendAssistantMessage(request.getSessionId(), userId, response.getAnswer());
        return response;
    }

    /**
     * 流式问答：意图照常路由；faq/policy 走 knowledge /ask/stream 逐 token 经 sink 推；
     * 其它意图阻塞跑完整段、meta + 整段单 token（降级）。记忆同步。
     * 注：流式 faq/policy 暂不写 agent_trace（阻塞路径仍写），属已知取舍。
     */
    public AgentResponse chatStream(AgentRequest request, AgentStreamSink sink) {
        long start = System.currentTimeMillis();
        if (request.getTenantId() == null) {
            request.setTenantId(SecurityUtils.currentTenantId());
        }
        IntentResult intentResult = routerAgent.route(request);
        String intent = intentResult.getIntent();

        AgentResponse response;
        if ("faq".equals(intent) || "policy".equals(intent)) {
            response = streamKnowledgeAnswer(request, intent, sink);
        } else {
            response = dispatch(intent, request);
            sink.meta(buildAgentMeta(intent, response.getKbId(), response.getReferences()));
            if (StringUtils.hasText(response.getAnswer())) {
                sink.token(response.getAnswer());
            }
        }

        response.setIntent(intent);
        response.setConfidence(intentResult.getConfidence());
        response.setSessionId(request.getSessionId());
        response.setDurationMs(System.currentTimeMillis() - start);

        Long userId = SecurityUtils.currentUserId();
        shortTermMemory.appendUserMessage(request.getSessionId(), userId, request.getQuery());
        shortTermMemory.appendAssistantMessage(request.getSessionId(), userId, response.getAnswer());
        return response;
    }

    /** faq/policy：选库 + knowledge /ask/stream 逐 token 转发到 sink。 */
    private AgentResponse streamKnowledgeAnswer(AgentRequest request, String intent, AgentStreamSink sink) {
        Long defaultKbId = "policy".equals(intent)
                ? agentProperties.getKnowledge().getPolicyKbId()
                : agentProperties.getKnowledge().getFaqKbId();
        Integer topK = agentProperties.getKnowledge().getAskTopK();
        Long kbId = knowledgeBaseSelector.selectKbId(request, intent, defaultKbId, topK);

        AgentResponse response = new AgentResponse();
        response.setAgentName(intent);
        response.setKbId(kbId);
        response.setToolsCalled(new ArrayList<>(List.of("knowledge.ask/stream(kbId=" + kbId + ")")));
        StringBuilder buf = new StringBuilder();
        KnowledgeServiceClient.StreamAskResult result = knowledgeServiceClient.askStream(
                kbId, request.getQuery(), topK,
                meta -> {
                    List<KnowledgeReference> refs = toReferences(meta.getReferences());
                    response.setReferences(refs);
                    sink.meta(buildAgentMeta(intent, kbId, refs));
                },
                token -> {
                    buf.append(token);
                    sink.token(token);
                });
        response.setAnswer(StringUtils.hasText(result.answer()) ? result.answer() : buf.toString());
        response.setTokenUsed(result.tokenUsed());
        return response;
    }

    private Map<String, Object> buildAgentMeta(String intent, Long kbId, List<KnowledgeReference> references) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("mode", "agent");
        meta.put("answerSource", "agent");
        meta.put("intent", intent);
        meta.put("kbId", kbId);
        meta.put("referenceCount", references == null ? 0 : references.size());
        meta.put("references", references == null ? Collections.emptyList() : references);
        return meta;
    }

    private List<KnowledgeReference> toReferences(List<KnowledgeSearchHitClientResponse> references) {
        if (references == null) {
            return Collections.emptyList();
        }
        return references.stream().map(item -> {
            KnowledgeReference reference = new KnowledgeReference();
            reference.setChunkId(item.getChunkId());
            reference.setDocId(item.getDocId());
            reference.setKbId(item.getKbId());
            reference.setChunkIndex(item.getChunkIndex());
            reference.setTokenCount(item.getTokenCount());
            reference.setContent(item.getContent());
            reference.setScore(item.getScore());
            reference.setDenseScore(item.getDenseScore());
            reference.setSparseScore(item.getSparseScore());
            reference.setSource(item.getSource());
            return reference;
        }).toList();
    }

    private AgentResponse dispatch(String intent, AgentRequest request) {
        return switch (intent) {
            case "policy" -> policyAgent.execute(request);
            case "order" -> orderAgent.execute(request);
            case "ticket" -> ticketAgent.execute(request);
            case "escalation" -> escalationAgent.execute(request);
            default -> faqAgent.execute(request);
        };
    }
}
