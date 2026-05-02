package com.yyh.agent.agent;

import com.yyh.agent.dto.AgentRequest;
import com.yyh.agent.dto.AgentResponse;
import com.yyh.agent.dto.OrderInfo;
import com.yyh.agent.tool.OrderTool;
import com.yyh.agent.trace.AgentTraceService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.format.DateTimeFormatter;

@Component
public class OrderAgent extends BaseAgent {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final OrderTool orderTool;

    public OrderAgent(AgentTraceService agentTraceService, OrderTool orderTool) {
        super(agentTraceService);
        this.orderTool = orderTool;
    }

    @Override
    protected AgentResponse doExecute(AgentRequest request, AgentExecutionContext context) {
        String orderId = orderTool.extractOrderId(request.getQuery());
        AgentResponse response = new AgentResponse();
        if (!StringUtils.hasText(orderId)) {
            response.setAnswer("请提供订单号，我可以帮你查询订单状态和物流信息。");
            return response;
        }

        context.addTool("order.queryOrder(orderId=" + orderId + ")");
        OrderInfo orderInfo = orderTool.queryOrder(orderId);
        context.addToolResult("orderInfo", orderInfo == null ? "not_found" : orderInfo.getOrderStatus());

        if (orderInfo == null) {
            response.setAnswer("没有查询到订单号 " + orderId + " 的记录，请确认订单号是否正确。");
            return response;
        }

        response.setAnswer(String.format(
                "订单 %s 当前状态：%s；物流状态：%s；承运方：%s；运单号：%s；预计送达：%s；最近更新时间：%s。",
                orderInfo.getOrderId(),
                orderInfo.getOrderStatus(),
                orderInfo.getLogisticsStatus(),
                orderInfo.getLogisticsCompany(),
                orderInfo.getTrackingNo(),
                orderInfo.getExpectedDeliveryTime().format(TIME_FORMATTER),
                orderInfo.getLastUpdatedAt().format(TIME_FORMATTER)
        ));
        return response;
    }

    @Override
    protected String agentName() {
        return "order";
    }
}
