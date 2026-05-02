package com.yyh.agent.agent;

import com.yyh.agent.dto.AgentRequest;
import com.yyh.agent.dto.AgentResponse;
import com.yyh.agent.dto.TicketInfo;
import com.yyh.agent.support.SecurityUtils;
import com.yyh.agent.tool.TicketTool;
import com.yyh.agent.trace.AgentTraceService;
import org.springframework.stereotype.Component;

@Component
public class TicketAgent extends BaseAgent {

    private final TicketTool ticketTool;

    public TicketAgent(AgentTraceService agentTraceService, TicketTool ticketTool) {
        super(agentTraceService);
        this.ticketTool = ticketTool;
    }

    @Override
    protected AgentResponse doExecute(AgentRequest request, AgentExecutionContext context) {
        String type = inferType(request.getQuery());
        int priority = inferPriority(request.getQuery());
        context.addTool("ticket.createTicket(type=" + type + ",priority=" + priority + ")");
        TicketInfo ticketInfo = ticketTool.createTicket(
                SecurityUtils.currentTenantId(),
                request.getSessionId(),
                SecurityUtils.currentUserId(),
                type,
                priority,
                request.getQuery()
        );
        context.addToolResult("ticketId", ticketInfo.getTicketId());

        AgentResponse response = new AgentResponse();
        response.setAnswer(String.format(
                "已为你创建售后工单，工单号为 %s，类型：%s，优先级：%s。客服后续会跟进处理。",
                ticketInfo.getTicketId(),
                ticketInfo.getType(),
                priorityLabel(priority)
        ));
        return response;
    }

    @Override
    protected String agentName() {
        return "ticket";
    }

    private String inferType(String query) {
        if (query.contains("退款")) {
            return "退款";
        }
        if (query.contains("换货")) {
            return "换货";
        }
        if (query.contains("投诉")) {
            return "投诉";
        }
        return "售后咨询";
    }

    private int inferPriority(String query) {
        if (query.contains("严重") || query.contains("投诉") || query.contains("质量问题")) {
            return 1;
        }
        if (query.contains("尽快") || query.contains("着急")) {
            return 2;
        }
        return 3;
    }

    private String priorityLabel(int priority) {
        return switch (priority) {
            case 1 -> "紧急";
            case 2 -> "普通";
            default -> "低";
        };
    }
}
