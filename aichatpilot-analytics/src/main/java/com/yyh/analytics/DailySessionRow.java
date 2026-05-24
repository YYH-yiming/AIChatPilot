package com.yyh.analytics;

import lombok.Data;

import java.time.LocalDate;

@Data
public class DailySessionRow {

    private LocalDate statDate;
    private Long totalCount;
}
