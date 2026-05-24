package com.yyh.analytics;

import com.yyh.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Analytics统计分析")
@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsQueryService analyticsQueryService;

    @Operation(summary = "获取分析仪表盘")
    @GetMapping("/dashboard")
    public Result<AnalyticsDashboardVO> dashboard(@RequestParam(required = false) Integer days) {
        return Result.success(analyticsQueryService.getDashboard(days));
    }

    @Operation(summary = "获取整体概览")
    @GetMapping("/overview")
    public Result<AnalyticsOverviewVO> overview(@RequestParam(required = false) Integer days) {
        return Result.success(analyticsQueryService.getOverview(days));
    }

    @Operation(summary = "获取趋势数据")
    @GetMapping("/trends")
    public Result<List<AnalyticsTrendPointVO>> trends(@RequestParam(required = false) Integer days) {
        return Result.success(analyticsQueryService.getTrends(days));
    }

    @Operation(summary = "获取意图分布")
    @GetMapping("/intents")
    public Result<List<AnalyticsIntentStatVO>> intents(@RequestParam(required = false) Integer days) {
        return Result.success(analyticsQueryService.getIntents(days));
    }

    @Operation(summary = "获取回答来源分布")
    @GetMapping("/sources")
    public Result<List<AnalyticsSourceStatVO>> sources(@RequestParam(required = false) Integer days) {
        return Result.success(analyticsQueryService.getSources(days));
    }

    @Operation(summary = "获取性能统计")
    @GetMapping("/performance")
    public Result<AnalyticsPerformanceVO> performance(@RequestParam(required = false) Integer days) {
        return Result.success(analyticsQueryService.getPerformance(days));
    }
}
