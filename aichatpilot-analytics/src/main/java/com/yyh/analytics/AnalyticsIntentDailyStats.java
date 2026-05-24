package com.yyh.analytics;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("analytics_intent_daily_stats")
public class AnalyticsIntentDailyStats {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private LocalDate statDate;
    private String intent;
    private Long hitCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
