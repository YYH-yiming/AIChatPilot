package com.yyh.agent.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OrderInfo {
    private String orderId;
    private String itemName;
    private String orderStatus;
    private String logisticsStatus;
    private String logisticsCompany;
    private String trackingNo;
    private LocalDateTime expectedDeliveryTime;
    private LocalDateTime lastUpdatedAt;
}
