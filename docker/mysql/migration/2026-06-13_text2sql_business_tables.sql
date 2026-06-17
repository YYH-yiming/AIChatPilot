-- ============================================================
-- Text2SQL 结构化业务表（云策科技产品/价格）
-- 用途：为 NL→SQL 提供可精确查询的结构化数据（聚合/筛选/对比/计数），
--       补 RAG 文档检索在"算数/筛选"上的短板。
-- 数据来源（一字不改、不杜撰）：
--   docs/knowledgeDocs/01_产品中心库/03_智答版本功能对比.csv
--   docs/knowledgeDocs/01_产品中心库/08_产品规格与限额表.csv
--   docs/knowledgeDocs/02_价格与合同库/01_产品价格总表.csv
--   docs/knowledgeDocs/02_价格与合同库/03_增值服务价格表.csv
-- 可重复执行：先 DROP 再建（仅这两张业务表，不动其它）。
-- 约定：数值列 NULL = 不限/自定义（旗舰版）；audit_log_retention_days / max_dialog_turns
--       取值 0 = 该版本不支持该能力（基础版）。布尔列 1=支持(含"可选")，0=不支持。
-- ============================================================

USE aichatpilot;
SET NAMES utf8mb4;

-- ==================== 产品套餐宽表（智答四档对比） ====================
DROP TABLE IF EXISTS biz_product_plan;
CREATE TABLE biz_product_plan (
    id                         BIGINT PRIMARY KEY AUTO_INCREMENT,
    product                    VARCHAR(50)  NOT NULL COMMENT '产品线，如 云策·智答',
    plan_name                  VARCHAR(50)  NOT NULL COMMENT '版本名：基础版/专业版/企业版/旗舰版',
    plan_level                 TINYINT      NOT NULL COMMENT '版本档位：1基础 2专业 3企业 4旗舰，便于排序/比较',
    billing                    VARCHAR(20)  COMMENT '计费方式：年付/年付起',
    annual_price               INT          COMMENT '年付价格(元)',
    kb_count                   INT          COMMENT '知识库数量(个)；NULL=不限',
    doc_limit_per_kb           INT          COMMENT '单知识库文档数上限(篇)；NULL=不限',
    seat_count                 INT          COMMENT '起始坐席数(个)；NULL=不限（专业版起步10/企业版起步20可扩展）',
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
    has_private_deploy         TINYINT      DEFAULT 0 COMMENT '私有化/混合部署（企业版可选计为支持）',
    has_custom_dev             TINYINT      DEFAULT 0 COMMENT '定制开发',
    has_ab_test                TINYINT      DEFAULT 0 COMMENT '灰度发布与AB测试',
    has_24x7_hotline           TINYINT      DEFAULT 0 COMMENT '7x24客服热线',
    has_dedicated_csm          TINYINT      DEFAULT 0 COMMENT '专属客户成功经理（企业版可选计为支持）',
    has_data_export            TINYINT      DEFAULT 0 COMMENT '数据导出',
    included_summary           VARCHAR(255) COMMENT '价格表"说明"列：版本含量摘要',
    UNIQUE KEY uk_product_plan (product, plan_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产品套餐宽表（智答版本对比/规格，Text2SQL用）';

INSERT INTO biz_product_plan
(product, plan_name, plan_level, billing, annual_price, kb_count, doc_limit_per_kb, seat_count,
 monthly_api_calls, max_qps, max_concurrent_sessions, monthly_qa_limit, history_retention_days,
 audit_log_retention_days, max_dialog_turns, recall_top_k, language_count, sub_account_count,
 model_options, storage_gb, sla_availability, rto_hours, rpo_hours, ticket_priority,
 has_rerank, has_multi_turn, has_intent_routing, has_multi_agent, has_ticket_link, has_sso,
 has_open_api, has_webhook, has_multilang, has_private_deploy, has_custom_dev, has_ab_test,
 has_24x7_hotline, has_dedicated_csm, has_data_export, included_summary)
VALUES
('云策·智答','基础版',1,'年付',9800, 1,   200,   3,    10000,   5,   20,   30000,  30,  0,   0,  5,  1,  3,   1, 2,    99.50,8.0,24.0,'P3',
 0,0,0,0,0,0, 0,0,0,0,0,0, 0,0,0, '含1个知识库/3坐席/月1万次API'),
('云策·智答','专业版',2,'年付',29800, 5,   2000,  10,   500000,  20,  200,  300000, 90,  30,  20, 10, 1,  20,  2, 20,   99.90,4.0,12.0,'P2',
 1,1,0,0,1,0, 1,0,0,0,0,0, 0,0,1, '含5个知识库/10坐席起/月50万次API'),
('云策·智答','企业版',3,'年付',98000, 20,  20000, 20,   5000000, 100, 2000, 3000000,365, 180, 50, 20, 12, 200, 4, 200,  99.95,1.0,4.0,'P1',
 1,1,1,1,1,1, 1,1,1,1,0,1, 1,1,1, '含20个知识库/20坐席起/智能路由/多Agent/SSO'),
('云策·智答','旗舰版',4,'年付起',298000, NULL,NULL, NULL, NULL,  NULL,NULL, NULL,   NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL, 99.95,0.5,1.0,'P1',
 1,1,1,1,1,1, 1,1,1,1,1,1, 1,1,1, '含定制开发/私有化/专属客户成功');

-- ==================== 价格/增值服务目录长表 ====================
DROP TABLE IF EXISTS biz_price_item;
CREATE TABLE biz_price_item (
    id               BIGINT PRIMARY KEY AUTO_INCREMENT,
    category         VARCHAR(30)  NOT NULL COMMENT '分类：产品/坐席扩展/增值服务/私有化/培训/优惠/试用',
    item_name        VARCHAR(100) NOT NULL COMMENT '项目/服务名称',
    billing_mode     VARCHAR(30)  COMMENT '计费方式：年付/一次性/按量/按年结算/赠送/免费',
    unit             VARCHAR(30)  COMMENT '计费单位：个/年、100万次、条、分钟、人天、次、坐席/月 等',
    unit_price       DECIMAL(12,2) COMMENT '单价(元)；NULL=非数值价格（见 price_text）',
    price_text       VARCHAR(50)  COMMENT '非数值价格原文，如 基础订阅20% / 8.5折',
    min_order        INT          COMMENT '起订量；NULL=不适用',
    applicable_plan  VARCHAR(30)  COMMENT '适用版本：全部/专业版及以上/企业版及以上/企业版可选/旗舰版',
    note             VARCHAR(255) COMMENT '备注/说明',
    INDEX idx_category (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='价格与增值服务目录（Text2SQL用）';

INSERT INTO biz_price_item
(category, item_name, billing_mode, unit, unit_price, price_text, min_order, applicable_plan, note)
VALUES
-- 其它产品（非智答四档，来自 产品价格总表）
('产品','云策·工单 独立版','年付','年',19800,NULL,NULL,'全部','完整工单流程与SLA管理'),
('产品','云策·工单 随智答专业版','赠送','年',0,NULL,NULL,'专业版及以上','专业版及以上内置基础工单'),
('产品','云策·洞察 标准版','年付','年',39800,NULL,NULL,'全部','指标体系/看板/预警/报表订阅'),
('产品','云策·智体 标准版','年付起','年',49800,NULL,NULL,'全部','多Agent编排/工具调用/Trace'),
('产品','云策·智体 随智答企业版','赠送','年',0,NULL,NULL,'企业版及以上','企业版及以上内置多Agent协同'),
('坐席扩展','坐席扩展(专业版)','按年结算','坐席/月',99,NULL,NULL,'专业版','99元/坐席/月，按年结算等效1188元/坐席/年'),
('坐席扩展','坐席扩展(企业版)','按年结算','坐席/月',99,NULL,NULL,'企业版','99元/坐席/月，满50席享阶梯价'),
('试用','全功能试用','免费','次',0,NULL,NULL,'全部','14天，限单知识库与有限额度'),
('优惠','教育/非营利优惠(专业版)','年付','年',20860,NULL,NULL,'专业版','凭资质享7折'),
('优惠','多年付优惠(签3年)','年付','年',NULL,'8.5折',NULL,'全部','一次性签约3年享折扣'),
-- 增值服务（来自 增值服务价格表）
('增值服务','额外知识库','年付','个/年',2000,NULL,1,'专业版及以上','超出版本含量后按个计费'),
('增值服务','API调用包','按量','100万次',5000,NULL,1,'专业版及以上','超出月度额度后购买，不结转'),
('增值服务','高并发扩容包','年付','每10QPS/年',8000,NULL,1,'企业版及以上','临时大促可按月购买'),
('增值服务','短信通知','按量','条',0.05,NULL,10000,'全部','验证码与通知短信'),
('增值服务','语音通知','按量','分钟',0.12,NULL,1000,'专业版及以上','外呼语音通知'),
('增值服务','多语言扩展包','年付','年',15000,NULL,1,'企业版及以上','新增12种语言问答'),
('增值服务','灰度与AB测试模块','年付','年',18000,NULL,1,'企业版及以上','仅企业版可加购'),
('增值服务','高级审计日志','年付','年',10000,NULL,1,'企业版及以上','延长留存至自定义周期'),
('增值服务','专属客户成功经理','年付','年',30000,NULL,1,'企业版可选','旗舰版标配'),
('培训','上线陪跑专场培训','一次性','场',8000,NULL,1,'全部','4小时定制培训'),
('培训','管理员认证培训','一次性','人',2000,NULL,1,'全部','线上认证课程'),
('增值服务','数据迁移服务','一次性','次',12000,NULL,1,'全部','历史工单与知识批量迁移'),
('增值服务','定制开发','一次性','人天',3500,NULL,5,'企业版及以上','复杂项目另议'),
('私有化','私有化标准实施','一次性','次',150000,NULL,1,'企业版及以上','标准生产环境'),
('私有化','私有化高可用实施','一次性','次',300000,NULL,1,'旗舰版','高可用集群'),
('私有化','私有化年度维护','年付','年',NULL,'基础订阅20%',1,'企业版及以上','版本升级与远程支持'),
('增值服务','现场支持','一次性','人天',5000,NULL,1,'企业版及以上','差旅另计'),
('增值服务','专属驻场','一次性','人月',45000,NULL,1,'旗舰版','长期驻场支持'),
('增值服务','额外存储包','年付','100GB/年',3000,NULL,1,'专业版及以上','超出版本存储后购买'),
('增值服务','对话历史延长留存','年付','90天/年',4000,NULL,1,'企业版及以上','延长会话留存'),
('增值服务','专属子域名','年付','年',2000,NULL,1,'企业版及以上','自定义访问域名'),
('增值服务','品牌定制(白标)','年付','年',30000,NULL,1,'旗舰版','去云策标识与界面定制'),
('增值服务','SLA升级包','年付','年',20000,NULL,1,'企业版及以上','可用性承诺升级与赔付加强'),
('增值服务','应急演练服务','一次性','次',15000,NULL,1,'企业版及以上','故障应急联合演练'),
('增值服务','知识库健康巡检','一次性','次',6000,NULL,1,'专业版及以上','季度命中率与缺口分析');
