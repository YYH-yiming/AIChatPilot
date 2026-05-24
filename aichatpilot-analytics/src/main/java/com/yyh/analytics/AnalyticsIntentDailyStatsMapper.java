package com.yyh.analytics;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AnalyticsIntentDailyStatsMapper extends BaseMapper<AnalyticsIntentDailyStats> {

    @Insert("""
            INSERT INTO analytics_intent_daily_stats (tenant_id, stat_date, intent, hit_count)
            VALUES (#{tenantId}, #{statDate}, #{intent}, #{hitCount})
            ON DUPLICATE KEY UPDATE hit_count = hit_count + VALUES(hit_count)
            """)
    int accumulate(AnalyticsIntentDailyStats stats);
}
