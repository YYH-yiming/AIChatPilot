-- ============================================================
-- AIChatPilot 数据库初始化脚本
-- ============================================================

CREATE DATABASE IF NOT EXISTS aichatpilot DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;
USE aichatpilot;

-- ==================== 系统用户表（补充） ====================

CREATE TABLE sys_user (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id       BIGINT,
    username        VARCHAR(50)  NOT NULL UNIQUE,
    password        VARCHAR(200) NOT NULL COMMENT 'BCrypt加密',
    nickname        VARCHAR(100),
    email           VARCHAR(100),
    phone           VARCHAR(20),
    role            VARCHAR(20)  DEFAULT 'user' COMMENT 'admin/user',
    status          TINYINT      DEFAULT 1 COMMENT '0-禁用 1-启用',
    created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户表';

-- ==================== 租户模块 ====================

CREATE TABLE tenant (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    name            VARCHAR(100) NOT NULL COMMENT '租户名称',
    api_key_config  JSON         COMMENT 'LLM API Key配置（加密存储）',
    model_config    JSON         COMMENT '模型选择配置（主模型/备用模型）',
    max_qps         INT          DEFAULT 100 COMMENT '租户级QPS限制',
    status          TINYINT      DEFAULT 1 COMMENT '0-禁用 1-启用',
    created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户表';

-- ==================== 知识库模块 ====================

CREATE TABLE knowledge_base (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id       BIGINT       NOT NULL,
    name            VARCHAR(200) NOT NULL COMMENT '知识库名称',
    description     TEXT         COMMENT '知识库描述',
    doc_count       INT          DEFAULT 0 COMMENT '文档数量',
    chunk_count     INT          DEFAULT 0 COMMENT '切片总数',
    embedding_model VARCHAR(100) COMMENT '使用的Embedding模型',
    status          TINYINT      DEFAULT 1 COMMENT '0-构建中 1-可用 2-更新中',
    created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库';

CREATE TABLE knowledge_document (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    kb_id           BIGINT        NOT NULL COMMENT '所属知识库ID',
    file_name       VARCHAR(500)  NOT NULL COMMENT '文件名',
    file_url        VARCHAR(1000) COMMENT 'MinIO存储路径',
    file_size       BIGINT        COMMENT '文件大小(bytes)',
    file_type       VARCHAR(20)   COMMENT '文件类型(pdf/docx/md)',
    parse_status    TINYINT       DEFAULT 0 COMMENT '0-待解析 1-解析中 2-完成 3-失败',
    chunk_count     INT           DEFAULT 0 COMMENT '切片数量',
    error_msg       TEXT          COMMENT '解析失败原因',
    created_at      DATETIME      DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_kb (kb_id),
    INDEX idx_status (parse_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识文档';

CREATE TABLE knowledge_chunk (
    id              BIGINT       PRIMARY KEY AUTO_INCREMENT,
    doc_id          BIGINT       NOT NULL COMMENT '所属文档ID',
    kb_id           BIGINT       NOT NULL COMMENT '所属知识库ID（冗余，方便查询）',
    content         TEXT         NOT NULL COMMENT '切片文本内容',
    token_count     INT          COMMENT 'Token数量',
    chunk_index     INT          COMMENT '在文档中的序号',
    vector_id       VARCHAR(100) COMMENT 'Milvus中的向量ID',
    metadata        JSON         COMMENT '元数据（页码、标题等）',
    created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_doc (doc_id),
    INDEX idx_kb (kb_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识切片';

-- ==================== 会话模块 ====================

CREATE TABLE chat_session (
    id              BIGINT       PRIMARY KEY AUTO_INCREMENT,
    tenant_id       BIGINT       NOT NULL,
    user_id         VARCHAR(100) NOT NULL COMMENT '终端用户标识',
    kb_id           BIGINT       COMMENT '关联的知识库',
    status          TINYINT      DEFAULT 1 COMMENT '0-已结束 1-进行中 2-已转人工',
    message_count   INT          DEFAULT 0,
    total_tokens    INT          DEFAULT 0 COMMENT '总Token消耗',
    satisfaction    TINYINT      COMMENT '用户满意度评分(1-5)',
    created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP,
    ended_at        DATETIME,
    INDEX idx_tenant_user (tenant_id, user_id),
    INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='聊天会话';

CREATE TABLE chat_message (
    id              BIGINT       PRIMARY KEY AUTO_INCREMENT,
    session_id      BIGINT       NOT NULL,
    role            VARCHAR(20)  NOT NULL COMMENT 'user/assistant/system',
    content         TEXT         NOT NULL,
    token_count     INT          COMMENT '本条消息Token数',
    model_used      VARCHAR(100) COMMENT '使用的模型名称',
    latency_ms      INT          COMMENT '响应延迟(毫秒)',
    created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session (session_id),
    INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='聊天消息';

-- ==================== 工单模块 ====================

CREATE TABLE ticket (
    id              BIGINT       PRIMARY KEY AUTO_INCREMENT,
    tenant_id       BIGINT       NOT NULL,
    session_id      BIGINT       COMMENT '来源会话',
    user_id         VARCHAR(100) NOT NULL,
    type            VARCHAR(50)  NOT NULL COMMENT '退款/换货/投诉/咨询',
    priority        TINYINT      DEFAULT 2 COMMENT '1-紧急 2-普通 3-低',
    status          TINYINT      DEFAULT 0 COMMENT '0-待处理 1-处理中 2-已完成 3-已关闭',
    assigned_to     VARCHAR(100) COMMENT '分配给谁',
    description     TEXT         COMMENT 'AI生成的工单描述',
    resolution      TEXT         COMMENT '处理结果',
    created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP,
    resolved_at     DATETIME,
    INDEX idx_tenant (tenant_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工单';

-- ==================== Agent追踪模块 ====================

CREATE TABLE agent_trace (
    id              BIGINT       PRIMARY KEY AUTO_INCREMENT,
    session_id      BIGINT       NOT NULL COMMENT '所属会话',
    message_id      BIGINT       COMMENT '触发的消息ID',
    agent_name      VARCHAR(50)  NOT NULL COMMENT 'router/faq/order/ticket/policy/escalation',
    parent_trace_id BIGINT       COMMENT '父Trace（Router调用子Agent时记录）',
    input_text      TEXT         COMMENT '输入内容',
    output_text     TEXT         COMMENT '输出内容',
    tools_called    JSON         COMMENT '调用的工具列表及参数',
    tool_results    JSON         COMMENT '工具返回结果',
    token_used      INT          COMMENT 'Token消耗',
    duration_ms     INT          COMMENT '处理耗时(毫秒)',
    status          VARCHAR(20)  DEFAULT 'success' COMMENT 'success/failed/timeout',
    error_msg       TEXT         COMMENT '错误信息',
    created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session (session_id),
    INDEX idx_agent (agent_name),
    INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent调用日志';

-- ==================== 初始数据 ====================

-- 插入默认租户
INSERT INTO tenant (name, max_qps, status) VALUES ('默认租户', 100, 1);

-- 插入管理员用户（密码: admin123，BCrypt加密）
INSERT INTO sys_user (tenant_id, username, password, nickname, role, status)
VALUES (1, 'admin', '$2a$10$EqKcp1WFKVQISheBxnGJGO0ROSB5N/C5fFOIBJPKOSWbkNmEOxcCG', '管理员', 'admin', 1);
