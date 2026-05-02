package com.yyh.agent.tool;

import com.yyh.agent.dto.TicketInfo;
import com.yyh.agent.mock.MockTicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TicketTool {

    private final MockTicketService mockTicketService;

    public TicketInfo createTicket(Long tenantId,
                                   Long sessionId,
                                   Long userId,
                                   String type,
                                   Integer priority,
                                   String description) {
        return mockTicketService.createTicket(tenantId, sessionId, userId, type, priority, description);
    }

    public TicketInfo queryTicket(Long ticketId) {
        return mockTicketService.findById(ticketId);
    }
}
