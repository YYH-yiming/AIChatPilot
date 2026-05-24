CREATE TABLE IF NOT EXISTS chat_session (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    title VARCHAR(128) NOT NULL,
    mode VARCHAR(32) NOT NULL,
    kb_id BIGINT NULL,
    status TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    last_message_at DATETIME NULL,
    INDEX idx_chat_session_user (user_id, tenant_id, status),
    INDEX idx_chat_session_last_message (last_message_at)
);

CREATE TABLE IF NOT EXISTS chat_message (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(32) NOT NULL,
    content LONGTEXT NOT NULL,
    answer_source VARCHAR(32) NULL,
    intent VARCHAR(32) NULL,
    kb_id BIGINT NULL,
    token_used INT NULL,
    duration_ms BIGINT NULL,
    reference_data LONGTEXT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_chat_message_session (session_id, created_at),
    INDEX idx_chat_message_user (user_id, tenant_id)
);
