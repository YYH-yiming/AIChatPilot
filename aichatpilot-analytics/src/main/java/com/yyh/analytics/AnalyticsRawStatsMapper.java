package com.yyh.analytics;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AnalyticsRawStatsMapper {

    @Select("""
            SELECT DATE(created_at) AS statDate, COUNT(*) AS totalCount
            FROM chat_session
            WHERE tenant_id = #{tenantId}
              AND created_at >= #{startTime}
              AND created_at < #{endTime}
            GROUP BY DATE(created_at)
            ORDER BY statDate
            """)
    List<DailySessionRow> selectSessionCreatedDailyRows(@Param("tenantId") Long tenantId,
                                                        @Param("startTime") LocalDateTime startTime,
                                                        @Param("endTime") LocalDateTime endTime);

    @Select("""
            SELECT DATE(updated_at) AS statDate, COUNT(*) AS totalCount
            FROM chat_session
            WHERE tenant_id = #{tenantId}
              AND status = 0
              AND updated_at >= #{startTime}
              AND updated_at < #{endTime}
            GROUP BY DATE(updated_at)
            ORDER BY statDate
            """)
    List<DailySessionRow> selectSessionClosedDailyRows(@Param("tenantId") Long tenantId,
                                                       @Param("startTime") LocalDateTime startTime,
                                                       @Param("endTime") LocalDateTime endTime);

    @Select("""
            SELECT DATE(created_at) AS statDate,
                   COUNT(*) AS messagesTotal,
                   SUM(CASE WHEN role = 'user' THEN 1 ELSE 0 END) AS userMessages,
                   SUM(CASE WHEN role = 'assistant' THEN 1 ELSE 0 END) AS assistantMessages,
                   SUM(CASE WHEN role = 'assistant' AND answer_source = 'knowledge' THEN 1 ELSE 0 END) AS knowledgeAnswers,
                   SUM(CASE WHEN role = 'assistant' AND answer_source = 'agent' THEN 1 ELSE 0 END) AS agentAnswers,
                   SUM(CASE
                       WHEN role = 'assistant' AND reference_data IS NOT NULL AND reference_data <> '' AND reference_data <> '[]'
                       THEN JSON_LENGTH(reference_data)
                       ELSE 0
                   END) AS referencesTotal,
                   SUM(CASE WHEN role = 'assistant' THEN COALESCE(token_used, 0) ELSE 0 END) AS totalTokens,
                   SUM(CASE WHEN role = 'assistant' THEN COALESCE(duration_ms, 0) ELSE 0 END) AS totalDurationMs,
                   SUM(CASE WHEN role = 'assistant' AND intent = 'escalation' THEN 1 ELSE 0 END) AS escalationCount
            FROM chat_message
            WHERE tenant_id = #{tenantId}
              AND created_at >= #{startTime}
              AND created_at < #{endTime}
            GROUP BY DATE(created_at)
            ORDER BY statDate
            """)
    List<DailyMessageRow> selectMessageDailyRows(@Param("tenantId") Long tenantId,
                                                 @Param("startTime") LocalDateTime startTime,
                                                 @Param("endTime") LocalDateTime endTime);

    @Select("""
            SELECT DATE(t.created_at) AS statDate,
                   COUNT(*) AS agentCalls,
                   SUM(CASE WHEN t.status = 'success' THEN 1 ELSE 0 END) AS agentSuccessCalls,
                   SUM(CASE WHEN t.status = 'failed' THEN 1 ELSE 0 END) AS agentFailedCalls
            FROM agent_trace t
            INNER JOIN chat_session s ON s.id = t.session_id
            WHERE s.tenant_id = #{tenantId}
              AND t.agent_name <> 'router'
              AND t.created_at >= #{startTime}
              AND t.created_at < #{endTime}
            GROUP BY DATE(t.created_at)
            ORDER BY statDate
            """)
    List<DailyAgentRow> selectAgentDailyRows(@Param("tenantId") Long tenantId,
                                             @Param("startTime") LocalDateTime startTime,
                                             @Param("endTime") LocalDateTime endTime);

    @Select("""
            SELECT COUNT(DISTINCT user_id)
            FROM chat_session
            WHERE tenant_id = #{tenantId}
              AND created_at >= #{startTime}
              AND created_at < #{endTime}
            """)
    Long countUniqueUsers(@Param("tenantId") Long tenantId,
                          @Param("startTime") LocalDateTime startTime,
                          @Param("endTime") LocalDateTime endTime);

    @Select("""
            SELECT answer_source AS name, COUNT(*) AS totalCount
            FROM chat_message
            WHERE tenant_id = #{tenantId}
              AND role = 'assistant'
              AND answer_source IS NOT NULL
              AND created_at >= #{startTime}
              AND created_at < #{endTime}
            GROUP BY answer_source
            ORDER BY totalCount DESC
            """)
    List<NameCountRow> selectSourceStats(@Param("tenantId") Long tenantId,
                                         @Param("startTime") LocalDateTime startTime,
                                         @Param("endTime") LocalDateTime endTime);

    @Select("""
            SELECT intent AS name, COUNT(*) AS totalCount
            FROM chat_message
            WHERE tenant_id = #{tenantId}
              AND role = 'assistant'
              AND intent IS NOT NULL
              AND intent <> ''
              AND created_at >= #{startTime}
              AND created_at < #{endTime}
            GROUP BY intent
            ORDER BY totalCount DESC
            """)
    List<NameCountRow> selectIntentStats(@Param("tenantId") Long tenantId,
                                         @Param("startTime") LocalDateTime startTime,
                                         @Param("endTime") LocalDateTime endTime);
}
