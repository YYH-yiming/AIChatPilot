package com.yyh.analytics;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("analytics_daily_stats")
public class AnalyticsDailyStats {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private LocalDate statDate;
    private Long sessionsCreated;
    private Long sessionsClosed;
    private Long messagesTotal;
    private Long userMessages;
    private Long assistantMessages;
    private Long knowledgeAnswers;
    private Long agentAnswers;
    private Long groundedAnswers;
    private Long referencesTotal;
    private Long totalTokens;
    private Long totalDurationMs;
    private Long agentCalls;
    private Long agentSuccessCalls;
    private Long agentFailedCalls;
    private Long escalationCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
