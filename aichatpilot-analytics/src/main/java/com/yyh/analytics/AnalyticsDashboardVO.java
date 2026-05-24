package com.yyh.analytics;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AnalyticsDashboardVO {

    private AnalyticsOverviewVO overview;
    private AnalyticsPerformanceVO performance;
    private List<AnalyticsTrendPointVO> trends = new ArrayList<>();
    private List<AnalyticsIntentStatVO> intents = new ArrayList<>();
    private List<AnalyticsSourceStatVO> sources = new ArrayList<>();
}
