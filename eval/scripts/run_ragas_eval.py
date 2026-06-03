#!/usr/bin/env python
"""RAGAs 质量层：对 /ask 的端到端回答评 faithfulness / answer_relevancy / context_precision / context_recall。

- faithfulness / response_relevancy：不需要 gold，对全部用例评；
- context_precision / context_recall：需要 reference(goldAnswer)，仅对有 gold 的子集评。

判分 LLM 与 embedding 走 OpenAI 兼容端点（豆包/方舟 + SiliconFlow bge），通过 .env 配置。
RAGAs 会发多次 LLM 调用，注意 token 成本；建议先 --limit 30 跑通再放大。
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


def build_evaluators():
    from langchain_openai import ChatOpenAI, OpenAIEmbeddings
    from ragas.llms import LangchainLLMWrapper
    from ragas.embeddings import LangchainEmbeddingsWrapper

    llm = ChatOpenAI(
        model=os.getenv("LLM_MODEL"),
        base_url=os.getenv("LLM_API_URL"),
        api_key=os.getenv("LLM_API_KEY"),
        temperature=0,
    )
    emb = OpenAIEmbeddings(
        model=os.getenv("EMBEDDING_MODEL", "BAAI/bge-large-zh-v1.5"),
        base_url=os.getenv("EMBEDDING_API_URL"),
        api_key=os.getenv("EMBEDDING_API_KEY"),
    )
    return LangchainLLMWrapper(llm), LangchainEmbeddingsWrapper(emb)


def main() -> None:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description="RAGAs 答案质量评测")
    ap.add_argument("--dataset", default=str(EVAL_ROOT / "datasets" / "eval_set.jsonl"))
    ap.add_argument("--arms", nargs="+", default=["C", "D"], choices=list(ARMS))
    ap.add_argument("--recall-top-n", type=int, default=20)
    ap.add_argument("--limit", type=int, default=0, help="只跑前 N 条（控成本）")
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
        from ragas.metrics import Faithfulness, ResponseRelevancy, LLMContextPrecisionWithReference, LLMContextRecall
        # v1.0被移除后就用下面这个引入方式
        # from ragas.metrics.collections import Faithfulness, ResponseRelevancy, LLMContextPrecisionWithReference, LLMContextRecall
    except ImportError as e:
        sys.exit(f"未安装 ragas 全家桶：{e}\n请 pip install -r requirements.txt")

    if not os.getenv("LLM_API_KEY") or not os.getenv("EMBEDDING_API_KEY"):
        sys.exit("缺少 LLM_API_KEY / EMBEDDING_API_KEY")

    eval_llm, eval_emb = build_evaluators()
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
                        llm=eval_llm, embeddings=eval_emb).to_pandas()

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
                              llm=eval_llm, embeddings=eval_emb).to_pandas()

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
