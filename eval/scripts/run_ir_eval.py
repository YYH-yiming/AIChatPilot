#!/usr/bin/env python
"""IR 指标 harness：对 dense-only / sparse-only / hybrid / hybrid+rerank 四组对照，
计算 Hit@k / MRR / Recall@k / Precision@k（doc 级 + chunk 级），导出对照表与逐用例明细。

依赖：eval_set.jsonl + 运行中的 knowledge 服务。
arm B/C/D 的按请求切换依赖 Java 侧覆盖参数（见实施方案 §4）；未改 Java 前 dense/sparse 字段被忽略。
"""
from __future__ import annotations

import argparse
import json
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
from lib import metrics as M  # noqa: E402
from lib import report as R  # noqa: E402

# arm -> 检索覆盖参数
ARMS = {
    "A": {"label": "A 普通RAG(dense-only)", "dense": True, "sparse": False, "rerank": False},
    "B": {"label": "B sparse-only", "dense": False, "sparse": True, "rerank": False},
    "C": {"label": "C 多路召回(hybrid+RRF)", "dense": True, "sparse": True, "rerank": False},
    "D": {"label": "D hybrid+rerank", "dense": True, "sparse": True, "rerank": True},
}


def ordered_ids(hits: list[dict], key: str) -> list:
    out = []
    for h in hits:
        v = h.get(key)
        if v is not None:
            out.append(v)
    return out


def run_arm(client: KnowledgeClient, arm: str, cases: list[dict], top_k: int, ks, recall_top_n: int):
    cfg = ARMS[arm]
    per_case = []
    failures = 0
    for c in cases:
        is_neg = bool(c.get("negative")) or not c.get("expectedDocIds")
        try:
            hits = client.search(
                c["kbId"], c["question"], top_k=top_k,
                dense=cfg["dense"], sparse=cfg["sparse"],
                rerank=cfg["rerank"],
                recall_top_n=recall_top_n if cfg["rerank"] else None,
            )
        except (ApiError, Exception) as e:  # noqa: BLE001
            failures += 1
            print(f"  [arm {arm}] 检索失败 case={c.get('caseId')}: {e}")
            continue

        doc_ids = ordered_ids(hits, "docId")
        chunk_ids = ordered_ids(hits, "chunkId")
        top1_source = hits[0].get("source") if hits else None

        row = {
            "arm": arm, "caseId": c.get("caseId"), "kbId": c.get("kbId"),
            "questionType": c.get("questionType"), "source": c.get("source"),
            "negative": is_neg, "top1_source": top1_source,
        }
        if is_neg:
            # 负样本无 gold：记录是否仍“自信”返回了非兜底结果（误召回参考，越低越好）
            row["neg_confident_return"] = 1.0 if (hits and top1_source not in (None, "db")) else 0.0
        else:
            for name, val in M.evaluate_case(doc_ids, c["expectedDocIds"], ks).items():
                row[f"doc_{name}"] = val
            if c.get("expectedChunkIds"):
                for name, val in M.evaluate_case(chunk_ids, c["expectedChunkIds"], ks).items():
                    row[f"chunk_{name}"] = val
        per_case.append(row)
    return per_case, failures


def main() -> None:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description="IR 指标四组对照")
    ap.add_argument("--dataset", default=str(EVAL_ROOT / "datasets" / "eval_set.jsonl"))
    ap.add_argument("--arms", nargs="+", default=["A", "B", "C", "D"], choices=list(ARMS))
    ap.add_argument("--ks", nargs="+", type=int, default=[1, 3, 5])
    ap.add_argument("--top-k", type=int, default=5)
    ap.add_argument("--recall-top-n", type=int, default=20, help="arm D 精排前候选数")
    ap.add_argument("--limit", type=int, default=0, help="只跑前 N 条（冒烟）")
    ap.add_argument("--outdir", default=str(EVAL_ROOT / "results"))
    args = ap.parse_args()

    ds = Path(args.dataset)
    if not ds.exists():
        sys.exit(f"找不到数据集 {ds}，先跑 build_eval_set.py")
    cases = [json.loads(l) for l in ds.read_text(encoding="utf-8").splitlines() if l.strip()]
    if args.limit:
        cases = cases[: args.limit]
    pos = [c for c in cases if c.get("expectedDocIds")]
    neg = [c for c in cases if not c.get("expectedDocIds")]
    print(f"数据集 {len(cases)} 条（正样本 {len(pos)} / 负样本 {len(neg)}），arms={args.arms}")

    client = KnowledgeClient()
    all_rows: list[dict] = []
    for arm in args.arms:
        rows, fails = run_arm(client, arm, cases, args.top_k, tuple(args.ks), args.recall_top_n)
        all_rows.extend(rows)
        print(f"  arm {arm} 完成，{len(rows)} 条，失败 {fails} 条")

    try:
        import pandas as pd
    except ImportError:
        sys.exit("需要 pandas：pip install -r requirements.txt")

    df = pd.DataFrame(all_rows)
    outdir = Path(args.outdir)
    R.write_csv(all_rows, outdir / "ir_per_case.csv")

    maxk = max(args.ks)
    doc_metric_cols = [f"doc_hit@{k}" for k in args.ks] + [f"doc_recall@{maxk}", f"doc_precision@{maxk}", "doc_mrr"]
    # 1) doc 级总体对照表
    pos_df = df[~df["negative"]]
    overall = []
    for arm in args.arms:
        sub = pos_df[pos_df["arm"] == arm]
        row = {"Arm": ARMS[arm]["label"]}
        for col in doc_metric_cols:
            row[col] = round(sub[col].mean(), 4) if col in sub and len(sub) else None
        overall.append(row)
    overall_md = R.to_markdown_table(overall, ["Arm", *doc_metric_cols])

    # 2) 分题型 Hit@maxk（doc 级）
    types = [t for t in pos_df["questionType"].dropna().unique()]
    per_type = []
    for t in types:
        row = {"questionType": t}
        for arm in args.arms:
            sub = pos_df[(pos_df["arm"] == arm) & (pos_df["questionType"] == t)]
            row[f"{arm}:hit@{maxk}"] = round(sub[f"doc_hit@{maxk}"].mean(), 4) if len(sub) else None
        per_type.append(row)
    per_type_cols = ["questionType", *[f"{a}:hit@{maxk}" for a in args.arms]]
    per_type_md = R.to_markdown_table(per_type, per_type_cols)

    # 3) chunk 级总体（仅在有 chunk gold 的子集上）
    chunk_cols = [f"chunk_hit@{k}" for k in args.ks] + [f"chunk_recall@{maxk}", "chunk_mrr"]
    has_chunk = [c for c in chunk_cols if c in df.columns]
    chunk_md = ""
    if has_chunk:
        chunk_rows = []
        for arm in args.arms:
            sub = pos_df[(pos_df["arm"] == arm)].dropna(subset=has_chunk, how="all")
            row = {"Arm": ARMS[arm]["label"]}
            for col in has_chunk:
                row[col] = round(sub[col].mean(), 4) if len(sub) else None
            chunk_rows.append(row)
        chunk_md = "\n\n## chunk 级（仅含 chunk gold 的用例）\n\n" + R.to_markdown_table(chunk_rows, ["Arm", *has_chunk])

    # 4) 负样本误召回
    neg_md = ""
    if "neg_confident_return" in df.columns:
        neg_rows = []
        for arm in args.arms:
            sub = df[(df["arm"] == arm) & (df["negative"])]
            neg_rows.append({"Arm": ARMS[arm]["label"],
                             "neg_confident_return_rate": round(sub["neg_confident_return"].mean(), 4) if len(sub) else None})
        neg_md = "\n\n## 跨库负样本（confident_return 越低越好，仅供参考）\n\n" + \
                 R.to_markdown_table(neg_rows, ["Arm", "neg_confident_return_rate"])

    report = (
        f"# RAG IR 指标对照报告\n\n"
        f"- 数据集：`{ds.name}`，正样本 {len(pos)} / 负样本 {len(neg)}\n"
        f"- arms：{', '.join(args.arms)}；top_k={args.top_k}，ks={args.ks}，recallN(D)={args.recall_top_n}\n"
        f"- 逐用例明细：`ir_per_case.csv`\n\n"
        f"> 数字由脚本真实跑出，请勿手改。\n\n"
        f"## doc 级总体对照（主指标）\n\n{overall_md}\n\n"
        f"## 分题型 Hit@{maxk}（doc 级）\n\n{per_type_md}"
        f"{chunk_md}{neg_md}\n"
    )
    R.write_text(report, outdir / "ir_report.md")
    print(f"完成 -> {outdir / 'ir_report.md'}")
    print("\n" + overall_md)


if __name__ == "__main__":
    main()
