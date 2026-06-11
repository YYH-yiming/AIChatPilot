# perf/ — AIChatPilot 压测工具集

配套规划：[`../docs/性能压测与优化规划.md`](../docs/性能压测与优化规划.md) ·
执行记录：[`../docs/性能压测执行记录.md`](../docs/性能压测执行记录.md)

> 红线：所有指标必须由这些脚本**真实跑出**，不预填、不编造。`results/` 只放真实测得的数据。

## 这些脚本测什么

| 脚本 | 作用 | 对应场景 |
|------|------|---------|
| `common.py` | 共享库：登录/建会话/分位数/落盘/查询集 | — |
| `sse_ttft_client.py` | 异步 SSE 客户端，精确测 **TTFT** 与端到端 | S1 单链路瀑布 / S2 SSE 并发 |
| `bench_rest.py` | 异步 REST 压测，测延迟分位/RPS/状态码分布 | S3 非流式 / S4 检索 / S5 问答 / S6 Agent / S7 缓存 |
| `parse_perf_logs.py` | 把服务里的 `[PERF]` 日志聚合成**链路瀑布表** | 填 1.3 各段耗时 |
| `k6_rest.js` | k6 版 REST 压测（更高并发交叉验证，可选） | S3-S6 |
| `run_baseline.ps1` | 一键编排整套阶段 A 基线 | 全部 |

> SSE 的 TTFT 必须用 `sse_ttft_client.py`（k6 不内置 SSE）。`[PERF]` 日志由 T1 埋点产生（见执行记录）。

## 准备

```powershell
# 1) 复用 eval 的虚拟环境（httpx 随 openai 已装，无需新依赖）
..\eval\.venv\Scripts\Activate.ps1

# 2) 配置
copy config.example.env .env    # PERF_KB_ID 现在可选（见下）；查询集默认用 eval_set.jsonl

# 3) 起服务：基础设施 + user/gateway/knowledge/agent/chat（见 docs/本地测试指南_不依赖Docker.md）
```

## 查询集与知识库路由（重要）

默认查询集 `eval/datasets/eval_set.jsonl` 共 **104 题，每题自带真实 `kbId`（7~11）**，
脚本会把**每条查询打到它自己所属的知识库**——否则统一打一个 KB，大半查询会 miss、
走空检索兜底、根本不触发答案 LLM，基线就测歪了。

- 你的库映射：`客户服务政策库=7 产品价格与合同库=8 客服运营与工单流程库=9 公司介绍与销售口径库=10 内部办公与入职规范库=11`。
- `PERF_KB_ID` 因此**变成可选回退**：只有当某条查询没带 kbId 时才用它。
- **别**把 `PERF_QUERY_FILE` 指向 `seed_from_faq.jsonl`（它的 kbId 是占位 1，会全 miss）。

## 关于限流（务必先读）

限流按 `tenant→user→IP` 计数，**单账号压测会共享一个计数器**，吞吐被卡在路由阈值（chat≈40/s），
测出来是限流上限不是系统上限。所以分两步：

- **A1 容量/延迟基线 → 关限流**：启 gateway 前设 `GATEWAY_RATE_LIMIT_ENABLED=false`。
- **A2 限流有效性 → 开限流**：`GATEWAY_RATE_LIMIT_ENABLED=true`，跑 `run_baseline.ps1 -LimiterTest`，看 429 比例。

## 跑

```powershell
# 一键全套（A1，记得已关限流）
pwsh run_baseline.ps1

# 或手动单跑：
# 单链路瀑布（并发1×30）
python sse_ttft_client.py --mode knowledge --concurrency 1 --requests 30
# SSE 并发扫描
python sse_ttft_client.py --mode knowledge --sweep 1,2,4,8,16,32 --requests-per-level 40
# 纯问答并发扫描（kbId 自动按 eval_set 每题路由，无需 --kb-id）
python bench_rest.py --endpoint ask --sweep 1,4,8,16,32 --requests-per-level 60
# 缓存命中（同一 query 连打，命中其所属 KB 的精确缓存）
python bench_rest.py --endpoint ask --concurrency 1 --requests 20 --same-query

# A2 限流有效性（限流开启时）
pwsh run_baseline.ps1 -LimiterTest

# 链路瀑布（把各服务控制台/日志重定向到文件后）
python parse_perf_logs.py knowledge.log agent.log chat.log
```

## 输出

- `results/sse_<mode>_summary.csv` / `_per_request.csv` —— TTFT/端到端分位 + 逐条
- `results/rest_<endpoint>_summary.csv` / `_per_request.csv` —— 延迟分位/RPS/状态码分布
- `results/waterfall.csv` —— 各 stage（embed/dense/sparse/rerank/llm…）耗时分位

把这些数字回填到 `docs/性能压测与优化规划.md` 的"待测"表格。

## 注意

- 压测会**真实消耗 SiliconFlow token 额度**；基线与复测务必同模型、同 `max_tokens`、相近时段。
- 想剥离外部 LLM 抖动、单看系统侧改造收益，可在服务端接一个固定延迟的 mock LLM（见规划文档 2.2）。
- 单请求超时默认 SSE 180s / REST 120s，可用 `--timeout` 调整。
