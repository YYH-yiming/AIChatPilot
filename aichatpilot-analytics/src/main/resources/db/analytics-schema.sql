CREATE TABLE IF NOT EXISTS analytics_daily_stats (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    stat_date DATE NOT NULL,
    sessions_created BIGINT NOT NULL DEFAULT 0,
    sessions_closed BIGINT NOT NULL DEFAULT 0,
    messages_total BIGINT NOT NULL DEFAULT 0,
    user_messages BIGINT NOT NULL DEFAULT 0,
    assistant_messages BIGINT NOT NULL DEFAULT 0,
    knowledge_answers BIGINT NOT NULL DEFAULT 0,
    agent_answers BIGINT NOT NULL DEFAULT 0,
    grounded_answers BIGINT NOT NULL DEFAULT 0,
    references_total BIGINT NOT NULL DEFAULT 0,
    total_tokens BIGINT NOT NULL DEFAULT 0,
    total_duration_ms BIGINT NOT NULL DEFAULT 0,
    agent_calls BIGINT NOT NULL DEFAULT 0,
    agent_success_calls BIGINT NOT NULL DEFAULT 0,
    agent_failed_calls BIGINT NOT NULL DEFAULT 0,
    escalation_count BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_analytics_daily_stats (tenant_id, stat_date)
);

CREATE TABLE IF NOT EXISTS analytics_intent_daily_stats (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    stat_date DATE NOT NULL,
    intent VARCHAR(32) NOT NULL,
    hit_count BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_analytics_intent_daily_stats (tenant_id, stat_date, intent)
);
