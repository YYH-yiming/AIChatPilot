#!/usr/bin/env python
"""合并 seed(轨道1) + generated(轨道2) -> datasets/eval_set.jsonl。

做三件事：
1. 用快照把 seed 用例的 kbName->kbId、_docFile->docId 解析成真实 id（轨道1 离线拿不到 id）；
2. 透传 generated 用例（已带真实 id）；
3. 合成少量跨库负样本（把正样本问题挂到别的知识库，断言不应命中），供 ask/RAGAs 层测误召回。

依赖：datasets/chunks_snapshot.json（解析 seed id 用）。无快照时仅透传 generated 并告警。
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

EVAL_ROOT = Path(__file__).resolve().parents[1]


def load_jsonl(path: Path) -> list[dict]:
    if not path.exists():
        return []
    return [json.loads(l) for l in path.read_text(encoding="utf-8").splitlines() if l.strip()]


def build_indexes(snapshot: dict):
    """返回 (kbName->kbId, (kbId, fileName)->docId)。fileName 同时建 stem 索引做兜底。"""
    name_to_id: dict[str, int] = {}
    file_to_doc: dict[tuple[int, str], int] = {}
    stem_to_doc: dict[tuple[int, str], int] = {}
    for kb_id, base in snapshot.get("bases", {}).items():
        kid = int(kb_id)
        name_to_id[base.get("kbName", kb_id)] = kid
        for doc_id, doc in base.get("documents", {}).items():
            fname = doc.get("fileName", "")
            file_to_doc[(kid, fname)] = int(doc_id)
            stem_to_doc[(kid, Path(fname).stem)] = int(doc_id)
    return name_to_id, file_to_doc, stem_to_doc


def resolve_seed(case: dict, name_to_id, file_to_doc, stem_to_doc) -> dict | None:
    # kb_id = case.get("kbId") or name_to_id.get(case.get("kbName", ""))
    kb_id = name_to_id.get(case.get("kbName", ""))
    if kb_id is None:
        return None
    doc_file = case.get("_docFile", "")
    doc_id = file_to_doc.get((kb_id, doc_file)) or stem_to_doc.get((kb_id, Path(doc_file).stem))
    if doc_id is None:
        return None
    case = dict(case)
    case["kbId"] = kb_id
    case["expectedDocIds"] = [doc_id]
    return case


def main() -> None:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description="合并并解析 eval_set")
    ap.add_argument("--seed", default=str(EVAL_ROOT / "datasets" / "seed_from_faq.jsonl"))
    ap.add_argument("--generated", default=str(EVAL_ROOT / "datasets" / "generated.jsonl"))
    ap.add_argument("--snapshot", default=str(EVAL_ROOT / "datasets" / "chunks_snapshot.json"))
    ap.add_argument("--out", default=str(EVAL_ROOT / "datasets" / "eval_set.jsonl"))
    ap.add_argument("--negatives", type=int, default=12, help="合成跨库负样本数量")
    args = ap.parse_args()

    snap_path = Path(args.snapshot)
    if snap_path.exists():
        name_to_id, file_to_doc, stem_to_doc = build_indexes(json.loads(snap_path.read_text(encoding="utf-8")))
    else:
        name_to_id, file_to_doc, stem_to_doc = {}, {}, {}
        print("⚠️ 无快照：seed 用例无法解析真实 id，将被跳过。先跑 snapshot_chunks.py。")

    merged: list[dict] = []
    seen: set[tuple] = set()          # (kbId, question) 去重
    unresolved = 0

    def add(case: dict):
        key = (case.get("kbId"), case.get("question"))
        if key in seen:
            return
        seen.add(key)
        merged.append(case)

    # 轨道1：解析 id
    for c in load_jsonl(Path(args.seed)):
        r = resolve_seed(c, name_to_id, file_to_doc, stem_to_doc)
        if r is None:
            unresolved += 1
            continue
        add(r)

    # 轨道2：透传
    for c in load_jsonl(Path(args.generated)):
        if c.get("kbId") and c.get("expectedDocIds"):
            add(c)

    # 跨库负样本：把正样本挂到别的库
    positives = [c for c in merged if c.get("expectedDocIds")]
    kb_ids = sorted({c["kbId"] for c in positives})
    neg_count = 0
    if len(kb_ids) >= 2 and args.negatives > 0:
        for i, src in enumerate(positives):
            if neg_count >= args.negatives:
                break
            others = [k for k in kb_ids if k != src["kbId"]]
            wrong_kb = others[i % len(others)]
            neg = {
                "caseId": f"neg-{neg_count + 1:04d}",
                "kbId": wrong_kb,
                "kbName": next((c["kbName"] for c in positives if c["kbId"] == wrong_kb), str(wrong_kb)),
                "question": src["question"],
                "questionType": "跨库负样本",
                "expectedDocIds": [],
                "expectedChunkIds": [],
                "expectedKeywords": [],
                "goldAnswer": None,
                "needRewrite": False,
                "shouldNotHitKbIds": [src["kbId"]],
                "negative": True,
                "source": "negative",
            }
            key = (neg["kbId"], neg["question"])
            if key not in seen:
                seen.add(key)
                merged.append(neg)
                neg_count += 1

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8") as f:
        for c in merged:
            f.write(json.dumps(c, ensure_ascii=False) + "\n")

    pos = sum(1 for c in merged if c.get("expectedDocIds"))
    print(f"合并完成：{len(merged)} 条（正样本 {pos} / 跨库负样本 {neg_count}）-> {out}")
    if unresolved:
        print(f"  跳过未能解析真实 docId 的 seed 用例 {unresolved} 条（检查文件名是否与上传一致）。")


if __name__ == "__main__":
    main()
