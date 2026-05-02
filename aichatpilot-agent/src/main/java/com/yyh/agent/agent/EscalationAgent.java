package com.yyh.agent.agent;

import com.yyh.agent.dto.AgentRequest;
import com.yyh.agent.dto.AgentResponse;
import com.yyh.agent.trace.AgentTraceService;
import org.springframework.stereotype.Component;

@Component
public class EscalationAgent extends BaseAgent {

    public EscalationAgent(AgentTraceService agentTraceService) {
        super(agentTraceService);
    }

    @Override
    protected AgentResponse doExecute(AgentRequest request, AgentExecutionContext context) {
        AgentResponse response = new AgentResponse();
        response.setAnswer("已为你转接人工客服，请稍候，客服专员会尽快接入。");
        return response;
    }

    @Override
    protected String agentName() {
        return "escalation";
    }
}
