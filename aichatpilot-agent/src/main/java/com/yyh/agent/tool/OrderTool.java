package com.yyh.agent.tool;

import com.yyh.agent.dto.OrderInfo;
import com.yyh.agent.mock.MockOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class OrderTool {

    private static final Pattern ORDER_ID_PATTERN = Pattern.compile("(\\d{5,})");

    private final MockOrderService mockOrderService;

    public OrderInfo queryOrder(String orderId) {
        return mockOrderService.findByOrderId(orderId);
    }

    public String extractOrderId(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = ORDER_ID_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}
