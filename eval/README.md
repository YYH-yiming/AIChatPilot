# RAG 评测一条龙（eval/）

黑盒评测 `aichatpilot-knowledge` 的 RAG 检索与问答质量。两层：

- **IR 指标层**（`run_ir_eval.py`）：Hit@k / MRR / Recall@k / Precision@k，对 dense-only / sparse-only / hybrid / hybrid+rerank 四组对照。需要 gold 标注。
- **RAGAs 质量层**（`run_ragas_eval.py`）：faithfulness / answer_relevancy / context_precision / context_recall。

设计与方法论详见 `../docs/RAG评测一条龙实施方案.md`。

> ⚠️ 所有指标必须由脚本真实跑出，不得预填或编造。

---

## 1. 安装

```powershell
cd eval
python -m venv .venv
.venv\Scripts\Activate.ps1
pip install -r requirements.txt
copy config.example.env .env   # 然后编辑 .env（见第 3 节）
```

---

## 2. Key 放在哪（最容易搞混，先看这张表）

有**两套**环境，key 分别放，不要混：

| Key | 放哪个文件 | 谁在用 / 何时必需 |
| --- | --- | --- |
| `EMBEDDING_API_KEY` | **服务端** 仓库根 `.env.local` | dense 检索+索引，arm **A/C/D 必需** |
| `RERANK_API_KEY` | **服务端** 仓库根 `.env.local` | rerank，**仅 arm D 必需** |
| `LLM_API_KEY` | **服务端** 仓库根 `.env.local` | `/ask` 生成答案，跑 **RAGAs 必需** |
| `LLM_API_KEY` | **评测端** `eval/.env` | `generate_cases` 出题 + `run_ragas` 判分 |
| `EMBEDDING_API_KEY` | **评测端** `eval/.env` | `run_ragas` 判分用 embedding |

- **服务端 key**（`.env.local`）被 `scripts/run-knowledge.ps1` 读取，给运行中的 knowledge 服务用。
- **评测端 key**（`eval/.env`）给本目录的 Python 脚本用。
- **rerank 的 key 只在服务端**，`eval/.env` 里不需要——重排是服务内部调用。

### 2.1 服务端 `.env.local` 需要补的增量

把下面几行加到**仓库根目录的 `.env.local`**（你现在放 `EMBEDDING_API_KEY` 的同一个文件，已有的不用动）：

```dotenv
# rerank（arm D 必需；不配则 arm D 自动降级 == arm C，不报错但测不出 rerank 效果）
RERANK_API_KEY=<你的硅基流动 key，可与 EMBEDDING 同一个>
# 下面两项有默认值，一般不用填
# RERANK_API_URL=https://api.siliconflow.cn/v1/rerank
# RERANK_MODEL=BAAI/bge-reranker-v2-m3
```

> `KNOWLEDGE_RETRIEVAL_RERANK_ENABLED` 代码默认已改为 `true`（线上 `/ask`、`/search` 默认走 rerank）。评测不受影响——四个 arm 都在请求里显式带 `dense/sparse/rerankEnabled`（A/B/C 显式关、D 显式开），覆盖全局默认。要临时全局关掉就设 `KNOWLEDGE_RETRIEVAL_RERANK_ENABLED=false`。

---

## 3. 评测端 `eval/.env`

复制 `config.example.env` 为 `.env` 后填：

- `EVAL_AUTH_MODE`：`direct`（默认，直连 `:8082` 带 `X-User-Id`/`X-Tenant-Id`，最省事，不需要起 gateway/user）；或 `gateway`（填 `EVAL_USERNAME`/`EVAL_PASSWORD` 走 `:8080` 登录拿 JWT）。
- `KB_NAME_TO_ID`：上传文档后用 `GET /api/knowledge/bases` 查到真实 kbId 回填。
- `LLM_API_KEY` / `EMBEDDING_API_KEY`：仅在跑 `generate_cases` / `run_ragas` 时需要。
- `X_USER_ID` / `X_TENANT_ID`：direct 模式下要与拥有这些知识库的租户一致。

---

## 4. 一条龙执行顺序

| 步 | 命令 | 需要的前置 |
| --- | --- | --- |
| 0 | `python lib/metrics.py` + `python -m py_compile lib/*.py scripts/*.py` | 无（免费自检） |
| 1 | `python scripts/harvest_faq_questions.py` | 无（离线，已可跑出 40 条种子） |
| 2 | **重建并启动服务**：`..\scripts\run-knowledge.ps1` | 基础设施在跑 + 服务端 key |
| 3 | 上传 5 个库文档 → 查 kbId 回填 `KB_NAME_TO_ID` | 服务在跑 |
| 4 | `python scripts/snapshot_chunks.py` | 服务在跑 |
| 5 | `python scripts/build_eval_set.py` | 步 4 的快照 |
| 6 | `python scripts/run_ir_eval.py --arms A B C` | 服务端 `EMBEDDING_API_KEY` |
| 7 | （可选，扩样本）`python scripts/generate_cases.py` → 抽检 → 重跑步 5 | eval 端 `LLM_API_KEY` |
| 8 | （要测 rerank）配好服务端 `RERANK_API_KEY` 后：`run_ir_eval.py --arms A B C D` | 服务端 `RERANK_API_KEY` |
| 9 | `python scripts/run_ragas_eval.py --arms C D` | 服务端 + eval 端 `LLM_API_KEY` |

要点：
- **步 2 必须重跑**：`run-knowledge.ps1` 会先 `mvn install` 重新打包，我的 Java 改动（按请求切 arm、rerank）才会生效；旧实例会忽略覆盖参数。
- **最快出第一版 IR 表**：步 1→2→3→4→5→6，跳过 LLM 生成（步 7）和 rerank（步 8），直接用 40 条种子（doc 级 gold）跑 A/B/C。`run_ir_eval` 不调 LLM，服务端只要有 `EMBEDDING_API_KEY`。
- **arm 与 key 对应**：A/C 要 `EMBEDDING_API_KEY`；D 额外要 `RERANK_API_KEY`；RAGAs 要 `LLM_API_KEY`（服务端生成 + eval 端判分）。
- **RAGAs 调试**：判分指标出现空值/NaN 时加 `--strict`，让 `evaluate` 直接抛出被吞掉的真实异常（默认静默记 NaN）。先 `--arms C --limit 2 --strict` 跑两条定位问题，再放大。

---

## 5. 产物

- `datasets/eval_set.jsonl`：最终评测集
- `results/ir_report.md`：IR 四组对照表 + 分题型表（**简历核心数字**）
- `results/ir_per_case.csv`：逐用例命中名次（error analysis）
- `results/ragas_results.csv` / `ragas_report.md`：RAGAs 逐用例与汇总

---

## 6. 注意

- 评测期 FAQ 缓存不会干扰：带覆盖参数的 `/ask` 已自动绕过缓存；`/search` 本就不缓存。
- 自增 id 会随重新入库漂移：以 `chunks_snapshot.json` 为准，要重测别重灌库，重灌后重新跑步 4/5。
- `.env`、`results/`、`chunks_snapshot.json` 默认不入库（见 `.gitignore`）；评测集 jsonl 默认保留。

---

## 7. RAGAs 判分指标全是空/NaN 排查

判分链路是 **eval 端独立**的 OpenAI 兼容端点（`LLM_API_URL` / `LLM_MODEL` + `EMBEDDING_*`），与服务端无关。全 NaN 时按顺序查：

1. **判分 LLM 通不通**：`python scripts/test_llm.py`，应打印一句回复。报错就是 key / URL / 模型名问题。
2. **看真实异常**：`python scripts/run_ragas_eval.py --arms C --limit 2 --strict`（需 knowledge 服务在跑——它要先调 `/ask` 取样本）。`--strict` 会把默认被吞掉的异常抛出来：
   - `OutputParser` / `pydantic` / JSON 解析失败 → 判分模型太弱或带「思考」输出（小模型如 8B 常见）。换更强的指令模型，或关掉 thinking。
   - `404 Not Found` → `*_API_URL` 写成了完整端点（`…/chat/completions`）。脚本已用 `_api_root()` 自动收敛回根，正常填到 `…/v1` 即可。
   - `401` → `LLM_API_KEY` / `EMBEDDING_API_KEY` 不对。
3. 不加 `--strict` 时单条判分失败只会让该格记 NaN，汇总用 `mean(skipna=True)` 仍出数——所以「看起来全空」必须用 `--strict` 才能定位根因。
