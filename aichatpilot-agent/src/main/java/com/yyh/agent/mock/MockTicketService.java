package com.yyh.agent.mock;

import com.yyh.agent.dto.TicketInfo;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class MockTicketService {

    private final AtomicLong ticketIdGenerator = new AtomicLong(10000);
    private final Map<Long, TicketInfo> tickets = new ConcurrentHashMap<>();

    public TicketInfo createTicket(Long tenantId,
                                   Long sessionId,
                                   Long userId,
                                   String type,
                                   Integer priority,
                                   String description) {
        TicketInfo ticketInfo = new TicketInfo();
        ticketInfo.setTicketId(ticketIdGenerator.incrementAndGet());
        ticketInfo.setTenantId(tenantId);
        ticketInfo.setSessionId(sessionId);
        ticketInfo.setUserId(userId);
        ticketInfo.setType(type);
        ticketInfo.setPriority(priority);
        ticketInfo.setStatus("待处理");
        ticketInfo.setDescription(description);
        ticketInfo.setCreatedAt(LocalDateTime.now());
        tickets.put(ticketInfo.getTicketId(), ticketInfo);
        return ticketInfo;
    }

    public TicketInfo findById(Long ticketId) {
        return tickets.get(ticketId);
    }
}
