#!/usr/bin/env python
"""RAGAs 质量层：对 /ask 的端到端回答评 faithfulness / answer_relevancy / context_precision / context_recall。

- faithfulness / response_relevancy：不需要 gold，对全部用例评；
- context_precision / context_recall：需要 reference(goldAnswer)，仅对有 gold 的子集评。

判分 LLM 与 embedding 走 OpenAI 兼容端点（豆包/方舟 + SiliconFlow bge），通过 .env 配置。
RAGAs 会发多次 LLM 调用，注意 token 成本；建议先 --limit 30 跑通再放大。
指标出现 NaN/空值时用 --strict：让 evaluate 抛出被吞掉的真实异常（默认静默记 NaN），
先 `--arms C --limit 2 --strict` 定位问题再放大。
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

EVAL_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(EVAL_ROOT))

try:
    from dotenv import load_dotenv
    load_dotenv(EVAL_ROOT / ".env")
except ImportError:
    pass

from lib.client import KnowledgeClient, ApiError  # noqa: E402
from lib import report as R  # noqa: E402

ARMS = {
    "C": {"label": "C hybrid", "dense": True, "sparse": True, "rerank": False},
    "D": {"label": "D hybrid+rerank", "dense": True, "sparse": True, "rerank": True},
}


def collect_samples(client: KnowledgeClient, arm: str, cases: list[dict], recall_top_n: int):
    cfg = ARMS[arm]
    rows = []
    for c in cases:
        try:
            resp = client.ask(
                c["kbId"], c["question"],
                dense=cfg["dense"], sparse=cfg["sparse"], rerank=cfg["rerank"],
                recall_top_n=recall_top_n if cfg["rerank"] else None,
            )
        except (ApiError, Exception) as e:  # noqa: BLE001
            print(f"  [arm {arm}] ask 失败 case={c.get('caseId')}: {e}")
            continue
        contexts = [r.get("content", "") for r in (resp.get("references") or [])]
        rows.append({
            "caseId": c.get("caseId"),
            "kbId": c.get("kbId"),
            "questionType": c.get("questionType"),
            "user_input": c["question"],
            "response": resp.get("answer", ""),
            "retrieved_contexts": contexts or [""],
            "reference": c.get("goldAnswer"),
        })
    return rows


def _api_root(url: str | None) -> str | None:
    """langchain-openai / OpenAI SDK 把 base_url 当成 API 根，自己再补 /chat/completions
    或 /embeddings。若 .env 写成完整端点（…/v1/chat/completions），SDK 会拼成
    …/v1/chat/completions/chat/completions → 404 → RAGAs 静默记 NaN。这里统一收敛回根。"""
    if not url:
        return url
    root = url.rstrip("/")
    for suffix in ("/chat/completions", "/completions", "/embeddings"):
        if root.endswith(suffix):
            return root[: -len(suffix)]
    return root


def build_evaluators():
    from langchain_openai import ChatOpenAI, OpenAIEmbeddings
    from ragas.llms import LangchainLLMWrapper
    from ragas.embeddings import LangchainEmbeddingsWrapper

    model_name = os.getenv("LLM_MODEL") or ""
    llm_kwargs = {}
    # Qwen3 系列默认开启 thinking：先吐大量 <think> 推理 token，既拖慢单次调用
    # （轻松超过 RAGAs 默认 180s 超时 → TimeoutError），又常把结构化 JSON 顶掉触发重试。
    # 判分用不到链式推理，关掉它。（仅 Qwen3 认识这个参数，其他模型不下发以免 400。）
    if "qwen3" in model_name.lower():
        llm_kwargs["extra_body"] = {"enable_thinking": False}

    llm = ChatOpenAI(
        model=model_name,
        base_url=_api_root(os.getenv("LLM_API_URL")),
        api_key=os.getenv("LLM_API_KEY"),
        temperature=0,
        **llm_kwargs,
    )
    emb = OpenAIEmbeddings(
        model=os.getenv("EMBEDDING_MODEL", "BAAI/bge-large-zh-v1.5"),
        base_url=_api_root(os.getenv("EMBEDDING_API_URL")),
        api_key=os.getenv("EMBEDDING_API_KEY"),
        # langchain 默认 True：先用 tiktoken 把文本切成 token-id 整数数组再发
        # （OpenAI 官方接口特性）。硅基流动/bge 等国内兼容端点只收原始字符串，
        # 收到整数数组会报 400 code=20015 参数无效。关掉它走「直发字符串」路径。
        check_embedding_ctx_length=False,
    )
    # bypass_n=True：硅基流动的 DeepSeek 等端点不支持 OpenAI 的 n 参数（一次请求返回 n
    # 个候选）。RAGAs 的 ResponseRelevancy(strictness=3) 会请求 n=3 → 端点报
    # 400 code=20015「n must be less than 1, got 3」。开了它，wrapper 改成发 n 次
    # 「单候选」请求再拼起来，语义等价、兼容不支持 n 的端点（Qwen3-8B 恰好支持 n，所以
    # 旧的判分模型没暴露这个问题；换 DeepSeek 必须打开）。代价：relevancy 的判分调用变 3 倍。
    return LangchainLLMWrapper(llm, bypass_n=True), LangchainEmbeddingsWrapper(emb)


def main() -> None:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description="RAGAs 答案质量评测")
    ap.add_argument("--dataset", default=str(EVAL_ROOT / "datasets" / "eval_set.jsonl"))
    ap.add_argument("--arms", nargs="+", default=["C", "D"], choices=list(ARMS))
    ap.add_argument("--recall-top-n", type=int, default=20)
    ap.add_argument("--limit", type=int, default=0, help="只跑前 N 条（控成本）")
    ap.add_argument("--strict", action="store_true",
                    help="判分异常时直接抛出（调试用），而不是静默记 NaN")
    ap.add_argument("--max-workers", type=int, default=4,
                    help="RAGAs 判分并发数；免费/限流端点调小，避免 429 假死成超时")
    ap.add_argument("--timeout", type=int, default=600,
                    help="单条指标判分超时秒数（RAGAs 默认 180，慢端点需调大）")
    ap.add_argument("--outdir", default=str(EVAL_ROOT / "results"))
    args = ap.parse_args()

    ds = Path(args.dataset)
    if not ds.exists():
        sys.exit(f"找不到数据集 {ds}")
    cases = [json.loads(l) for l in ds.read_text(encoding="utf-8").splitlines() if l.strip()]
    # 负样本不参与质量评（无 gold 答案语义），仅用正样本
    cases = [c for c in cases if c.get("expectedDocIds")]
    if args.limit:
        cases = cases[: args.limit]

    try:
        from ragas import EvaluationDataset, evaluate
        from ragas.run_config import RunConfig
        from ragas.metrics import Faithfulness, ResponseRelevancy, LLMContextPrecisionWithReference, LLMContextRecall
        # v1.0被移除后就用下面这个引入方式
        # from ragas.metrics.collections import Faithfulness, ResponseRelevancy, LLMContextPrecisionWithReference, LLMContextRecall
    except ImportError as e:
        sys.exit(f"未安装 ragas 全家桶：{e}\n请 pip install -r requirements.txt")

    if not os.getenv("LLM_API_KEY") or not os.getenv("EMBEDDING_API_KEY"):
        sys.exit("缺少 LLM_API_KEY / EMBEDDING_API_KEY")

    eval_llm, eval_emb = build_evaluators()
    # 慢端点 + 免费 key：调大单条超时、调小并发，避免 429/排队被当成超时
    run_config = RunConfig(timeout=args.timeout, max_workers=args.max_workers)
    client = KnowledgeClient()

    import pandas as pd
    all_frames = []
    for arm in args.arms:
        rows = collect_samples(client, arm, cases, args.recall_top_n)
        if not rows:
            print(f"  arm {arm}：无可评样本，跳过")
            continue

        # 1) 无需 gold 的指标，对全部样本评
        base_ds = EvaluationDataset.from_list([
            {"user_input": r["user_input"], "response": r["response"], "retrieved_contexts": r["retrieved_contexts"]}
            for r in rows
        ])
        base = evaluate(dataset=base_ds, metrics=[Faithfulness(), ResponseRelevancy()],
                        llm=eval_llm, embeddings=eval_emb, run_config=run_config,
                        raise_exceptions=args.strict).to_pandas()

        # 2) 需 gold 的指标，仅对有 reference 的子集评
        ref_rows = [r for r in rows if r.get("reference")]
        ref_df = None
        if ref_rows:
            ref_ds = EvaluationDataset.from_list([
                {"user_input": r["user_input"], "response": r["response"],
                 "retrieved_contexts": r["retrieved_contexts"], "reference": r["reference"]}
                for r in ref_rows
            ])
            ref_df = evaluate(dataset=ref_ds, metrics=[LLMContextPrecisionWithReference(), LLMContextRecall()],
                              llm=eval_llm, embeddings=eval_emb, run_config=run_config,
                              raise_exceptions=args.strict).to_pandas()

        base.insert(0, "caseId", [r["caseId"] for r in rows])
        base.insert(1, "arm", arm)
        if ref_df is not None:
            ref_df.insert(0, "caseId", [r["caseId"] for r in ref_rows])
            keep = ["caseId"] + [c for c in ref_df.columns if c not in base.columns and c != "caseId"]
            base = base.merge(ref_df[keep], on="caseId", how="left")
        all_frames.append(base)
        print(f"  arm {arm} 完成，{len(rows)} 条")

    if not all_frames:
        sys.exit("没有任何结果")
    result = pd.concat(all_frames, ignore_index=True)
    outdir = Path(args.outdir)
    outdir.mkdir(parents=True, exist_ok=True)
    result.to_csv(outdir / "ragas_results.csv", index=False, encoding="utf-8-sig")

    # 汇总：每个 arm 各指标均值
    metric_cols = [c for c in result.columns if c not in ("caseId", "arm", "user_input", "response", "retrieved_contexts", "reference")]
    summary_rows = []
    for arm in args.arms:
        sub = result[result["arm"] == arm]
        if not len(sub):
            continue
        row = {"Arm": ARMS[arm]["label"]}
        for col in metric_cols:
            if col in sub:
                row[col] = round(sub[col].mean(skipna=True), 4)
        summary_rows.append(row)
    summary_md = R.to_markdown_table(summary_rows, ["Arm", *metric_cols])
    R.write_text(f"# RAGAs 质量汇总\n\n> LLM 判分有方差，temperature=0，建议固定 ragas 版本。\n\n{summary_md}\n",
                 outdir / "ragas_report.md")
    print(f"完成 -> {outdir / 'ragas_results.csv'} / ragas_report.md")
    print("\n" + summary_md)


if __name__ == "__main__":
    main()
