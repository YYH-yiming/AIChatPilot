package com.yyh.agent.mock;

import com.yyh.agent.dto.OrderInfo;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MockOrderService {

    private final Map<String, OrderInfo> orders = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        orders.put("12345", buildOrder(
                "12345",
                "AIChatPilot 企业版订阅",
                "已发货",
                "运输中，已到达杭州分拨中心",
                "顺丰速运",
                "SF1234567890"
        ));
        orders.put("20260428001", buildOrder(
                "20260428001",
                "智能客服知识库服务包",
                "已签收",
                "用户已签收",
                "中通快递",
                "ZT20260428001"
        ));
    }

    public OrderInfo findByOrderId(String orderId) {
        return orders.get(orderId);
    }

    private OrderInfo buildOrder(String orderId,
                                 String itemName,
                                 String orderStatus,
                                 String logisticsStatus,
                                 String logisticsCompany,
                                 String trackingNo) {
        OrderInfo orderInfo = new OrderInfo();
        orderInfo.setOrderId(orderId);
        orderInfo.setItemName(itemName);
        orderInfo.setOrderStatus(orderStatus);
        orderInfo.setLogisticsStatus(logisticsStatus);
        orderInfo.setLogisticsCompany(logisticsCompany);
        orderInfo.setTrackingNo(trackingNo);
        orderInfo.setExpectedDeliveryTime(LocalDateTime.now().plusDays(1));
        orderInfo.setLastUpdatedAt(LocalDateTime.now().minusHours(2));
        return orderInfo;
    }
}
