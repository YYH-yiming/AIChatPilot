package com.yyh.analytics;

import lombok.Data;

import java.time.LocalDate;

@Data
public class DailyAgentRow {

    private LocalDate statDate;
    private Long agentCalls;
    private Long agentSuccessCalls;
    private Long agentFailedCalls;
}
