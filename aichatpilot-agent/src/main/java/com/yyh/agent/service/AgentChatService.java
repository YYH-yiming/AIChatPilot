package com.yyh.agent.service;

import com.yyh.agent.agent.EscalationAgent;
import com.yyh.agent.agent.FaqAgent;
import com.yyh.agent.agent.OrderAgent;
import com.yyh.agent.agent.PolicyAgent;
import com.yyh.agent.agent.RouterAgent;
import com.yyh.agent.agent.TicketAgent;
import com.yyh.agent.dto.AgentRequest;
import com.yyh.agent.dto.AgentResponse;
import com.yyh.agent.dto.IntentResult;
import com.yyh.agent.memory.ShortTermMemory;
import com.yyh.agent.support.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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
