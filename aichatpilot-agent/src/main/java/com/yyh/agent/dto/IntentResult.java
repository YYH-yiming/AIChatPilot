package com.yyh.agent.dto;

import lombok.Data;

@Data
public class IntentResult {
    private String intent;
    private Double confidence;
    private String reason;
}
