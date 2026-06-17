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
    parent_id       BIGINT       NULL COMMENT '父块ID（父子切分：子块指向父块；父块/旧平铺块为NULL）',
    chunk_role      VARCHAR(16)  NULL COMMENT '块角色：parent/child；NULL=旧平铺块',
    metadata        JSON         COMMENT '元数据（页码、标题等）',
    created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_doc (doc_id),
    INDEX idx_kb (kb_id),
    INDEX idx_parent (parent_id)
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

-- ==================== Text2SQL 结构化业务表 ====================
-- 为 NL→SQL 提供可精确查询的产品/价格数据（聚合/筛选/对比/计数）。
-- 种子数据见 docker/mysql/migration/2026-06-13_text2sql_business_tables.sql。
-- 数值列 NULL=不限/自定义；audit_log_retention_days/max_dialog_turns 取 0=不支持；布尔列 1=支持(含可选) 0=不支持。

CREATE TABLE biz_product_plan (
    id                         BIGINT PRIMARY KEY AUTO_INCREMENT,
    product                    VARCHAR(50)  NOT NULL COMMENT '产品线，如 云策·智答',
    plan_name                  VARCHAR(50)  NOT NULL COMMENT '版本名：基础版/专业版/企业版/旗舰版',
    plan_level                 TINYINT      NOT NULL COMMENT '版本档位：1基础 2专业 3企业 4旗舰',
    billing                    VARCHAR(20)  COMMENT '计费方式：年付/年付起',
    annual_price               INT          COMMENT '年付价格(元)',
    kb_count                   INT          COMMENT '知识库数量(个)；NULL=不限',
    doc_limit_per_kb           INT          COMMENT '单知识库文档数上限(篇)；NULL=不限',
    seat_count                 INT          COMMENT '起始坐席数(个)；NULL=不限',
    monthly_api_calls          INT          COMMENT '月API调用量(次)；NULL=自定义',
    max_qps                    INT          COMMENT 'QPS峰值(次/秒)；NULL=自定义',
    max_concurrent_sessions    INT          COMMENT '并发会话数(个)；NULL=自定义',
    monthly_qa_limit           INT          COMMENT '月问答量上限(次)；NULL=自定义',
    history_retention_days     INT          COMMENT '对话历史留存(天)；NULL=自定义',
    audit_log_retention_days   INT          COMMENT '审计日志留存(天)；0=不支持，NULL=自定义',
    max_dialog_turns           INT          COMMENT '多轮对话最大轮次；0=不支持，NULL=自定义',
    recall_top_k               INT          COMMENT '单次检索召回片段数(个)；NULL=自定义',
    language_count             INT          COMMENT '多语言支持(种)；NULL=自定义',
    sub_account_count          INT          COMMENT '子账号数(个)；NULL=不限',
    model_options              INT          COMMENT '可选模型数(个)；NULL=自定义',
    storage_gb                 INT          COMMENT '知识库总存储(GB)；NULL=自定义',
    sla_availability           DECIMAL(5,2) COMMENT '可用性SLA(%)',
    rto_hours                  DECIMAL(4,1) COMMENT '故障恢复目标RTO(小时)',
    rpo_hours                  DECIMAL(4,1) COMMENT '数据恢复目标RPO(小时)',
    ticket_priority            VARCHAR(4)   COMMENT '工单优先级响应：P1/P2/P3',
    has_rerank                 TINYINT      DEFAULT 0 COMMENT '精排重排(rerank)',
    has_multi_turn             TINYINT      DEFAULT 0 COMMENT '多轮对话与上下文改写',
    has_intent_routing         TINYINT      DEFAULT 0 COMMENT '智能意图路由',
    has_multi_agent            TINYINT      DEFAULT 0 COMMENT '多Agent协同编排',
    has_ticket_link            TINYINT      DEFAULT 0 COMMENT '工单联动(转工单)',
    has_sso                    TINYINT      DEFAULT 0 COMMENT '单点登录(SSO)',
    has_open_api               TINYINT      DEFAULT 0 COMMENT '开放API',
    has_webhook                TINYINT      DEFAULT 0 COMMENT 'Webhook与回调',
    has_multilang              TINYINT      DEFAULT 0 COMMENT '多语言问答',
    has_private_deploy         TINYINT      DEFAULT 0 COMMENT '私有化/混合部署（可选计为支持）',
    has_custom_dev             TINYINT      DEFAULT 0 COMMENT '定制开发',
    has_ab_test                TINYINT      DEFAULT 0 COMMENT '灰度发布与AB测试',
    has_24x7_hotline           TINYINT      DEFAULT 0 COMMENT '7x24客服热线',
    has_dedicated_csm          TINYINT      DEFAULT 0 COMMENT '专属客户成功经理（可选计为支持）',
    has_data_export            TINYINT      DEFAULT 0 COMMENT '数据导出',
    included_summary           VARCHAR(255) COMMENT '价格表"说明"列：版本含量摘要',
    UNIQUE KEY uk_product_plan (product, plan_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产品套餐宽表（智答版本对比/规格，Text2SQL用）';

CREATE TABLE biz_price_item (
    id               BIGINT PRIMARY KEY AUTO_INCREMENT,
    category         VARCHAR(30)  NOT NULL COMMENT '分类：产品/坐席扩展/增值服务/私有化/培训/优惠/试用',
    item_name        VARCHAR(100) NOT NULL COMMENT '项目/服务名称',
    billing_mode     VARCHAR(30)  COMMENT '计费方式：年付/一次性/按量/按年结算/赠送/免费',
    unit             VARCHAR(30)  COMMENT '计费单位',
    unit_price       DECIMAL(12,2) COMMENT '单价(元)；NULL=非数值价格（见 price_text）',
    price_text       VARCHAR(50)  COMMENT '非数值价格原文，如 基础订阅20% / 8.5折',
    min_order        INT          COMMENT '起订量；NULL=不适用',
    applicable_plan  VARCHAR(30)  COMMENT '适用版本：全部/专业版及以上/企业版及以上/企业版可选/旗舰版',
    note             VARCHAR(255) COMMENT '备注/说明',
    INDEX idx_category (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='价格与增值服务目录（Text2SQL用）';

-- ==================== 初始数据 ====================

-- 插入默认租户
INSERT INTO tenant (name, max_qps, status) VALUES ('默认租户', 100, 1);

-- 插入管理员用户（密码: admin123，BCrypt加密）
INSERT INTO sys_user (tenant_id, username, password, nickname, role, status)
VALUES (1, 'admin', '$2a$10$EqKcp1WFKVQISheBxnGJGO0ROSB5N/C5fFOIBJPKOSWbkNmEOxcCG', '管理员', 'admin', 1);
