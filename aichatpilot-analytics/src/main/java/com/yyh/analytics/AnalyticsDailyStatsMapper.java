package com.yyh.analytics;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AnalyticsDailyStatsMapper extends BaseMapper<AnalyticsDailyStats> {

    @Insert("""
            INSERT INTO analytics_daily_stats (
                tenant_id, stat_date, sessions_created, sessions_closed, messages_total,
                user_messages, assistant_messages, knowledge_answers, agent_answers, grounded_answers,
                references_total, total_tokens, total_duration_ms, agent_calls, agent_success_calls,
                agent_failed_calls, escalation_count
            ) VALUES (
                #{tenantId}, #{statDate}, #{sessionsCreated}, #{sessionsClosed}, #{messagesTotal},
                #{userMessages}, #{assistantMessages}, #{knowledgeAnswers}, #{agentAnswers}, #{groundedAnswers},
                #{referencesTotal}, #{totalTokens}, #{totalDurationMs}, #{agentCalls}, #{agentSuccessCalls},
                #{agentFailedCalls}, #{escalationCount}
            )
            ON DUPLICATE KEY UPDATE
                sessions_created = sessions_created + VALUES(sessions_created),
                sessions_closed = sessions_closed + VALUES(sessions_closed),
                messages_total = messages_total + VALUES(messages_total),
                user_messages = user_messages + VALUES(user_messages),
                assistant_messages = assistant_messages + VALUES(assistant_messages),
                knowledge_answers = knowledge_answers + VALUES(knowledge_answers),
                agent_answers = agent_answers + VALUES(agent_answers),
                grounded_answers = grounded_answers + VALUES(grounded_answers),
                references_total = references_total + VALUES(references_total),
                total_tokens = total_tokens + VALUES(total_tokens),
                total_duration_ms = total_duration_ms + VALUES(total_duration_ms),
                agent_calls = agent_calls + VALUES(agent_calls),
                agent_success_calls = agent_success_calls + VALUES(agent_success_calls),
                agent_failed_calls = agent_failed_calls + VALUES(agent_failed_calls),
                escalation_count = escalation_count + VALUES(escalation_count)
            """)
    int accumulate(AnalyticsDailyStats stats);
}
