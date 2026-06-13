#!/usr/bin/env python
"""为同库内"同名不同格式"的重复文档统一 gold：把 expectedDocIds 与同库同 stem 的其它 docId 取并集。

场景：同一篇文档同时上传了 .md 和 .pdf（内容相同），检索可能命中任一版本；
若 gold 只标其中一个，另一个被判 miss，指标失真。本脚本据快照把同 stem 的 docId 全部并入 gold。
依赖 datasets/chunks_snapshot.json。原地改写指定 eval_set。
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

EVAL_ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description="同库同名(多格式)文档 gold 并集")
    ap.add_argument("--dataset", default=str(EVAL_ROOT / "datasets" / "eval_set_large.jsonl"))
    ap.add_argument("--snapshot", default=str(EVAL_ROOT / "datasets" / "chunks_snapshot.json"))
    args = ap.parse_args()

    snap = json.loads(Path(args.snapshot).read_text(encoding="utf-8"))
    doc_meta: dict[int, tuple[int, str]] = {}          # docId -> (kbId, stem)
    stem_group: dict[tuple[int, str], set[int]] = {}    # (kbId, stem) -> {docId}
    for kb_id, base in snap.get("bases", {}).items():
        kid = int(kb_id)
        for doc_id, doc in base.get("documents", {}).items():
            did = int(doc_id)
            stem = Path(doc.get("fileName", "")).stem
            doc_meta[did] = (kid, stem)
            stem_group.setdefault((kid, stem), set()).add(did)

    ds = Path(args.dataset)
    cases = [json.loads(l) for l in ds.read_text(encoding="utf-8").splitlines() if l.strip()]
    patched = 0
    for c in cases:
        exp = c.get("expectedDocIds") or []
        if not exp:
            continue
        union = set(exp)
        for did in exp:
            kid, stem = doc_meta.get(did, (None, None))
            if kid is not None:
                union |= stem_group.get((kid, stem), set())
        if union != set(exp):
            c["expectedDocIds"] = sorted(union)
            patched += 1

    with ds.open("w", encoding="utf-8") as f:
        for c in cases:
            f.write(json.dumps(c, ensure_ascii=False) + "\n")
    print(f"已处理 {ds.name}：{patched} 条用例的 gold 因同库同名(多格式)文档做了并集扩充。")


if __name__ == "__main__":
    main()
