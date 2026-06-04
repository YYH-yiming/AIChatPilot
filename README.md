# AIChatPilot

AIChatPilot 是一个基于 Spring Boot / Spring Cloud 的 Maven 多模块项目，完成了知识库 RAG、混合检索、会话管理、Agent 编排、统计分析等核心链路。

## 模块概览

- `aichatpilot-common`：公共返回体、异常处理、分页、JWT 工具
- `aichatpilot-user`：注册、登录、用户信息、租户管理
- `aichatpilot-gateway`：统一入口、鉴权、路由转发、限流
- `aichatpilot-knowledge`：知识库 CRUD、文档上传、切片、Kafka 异步处理、Embedding、Milvus / ES 混合检索、RAG 问答
- `aichatpilot-agent`：Router Agent、多 Agent 编排、工具调用、短期记忆、Trace
- `aichatpilot-chat`：会话管理、消息收发、多轮上下文、SSE 流式输出
- `aichatpilot-analytics`：会话与 Agent 统计、仪表盘、趋势、来源、意图、性能分析
- `aichatpilot-mcp-server`：预留中的 MCP 工具服务骨架


## 端口约定
统一从gateway接受请求
- `gateway`：`8080`
- `user`：`8081`
- `knowledge`：`8082`
- `agent`：`8083`
- `chat`：`8084`
- `analytics`：`8085`
- `mcp-server`：`8086`

基础设施默认端口：

- MySQL：`3306`
- Redis：`6379`
- Nacos：`8848`
- MinIO：`9000` / `9001`
- Kafka：`9095`
- Elasticsearch：`9200`
- Milvus：`19530`

## 启动建议

优先使用仓库内脚本启动本机联调环境：

```powershell
.\scripts\run-local-stack.ps1
```

单模块启动可使用：

```powershell
.\scripts\run-user.ps1
.\scripts\run-gateway.ps1
.\scripts\run-knowledge.ps1
.\scripts\run-agent.ps1
.\scripts\run-chat.ps1
.\scripts\run-analytics.ps1
```


