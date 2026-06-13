#!/usr/bin/env python
"""探测当前检索口径是 flat 还是 parent-child。

判据：对每个库发一个查询，看返回 hit 的内容长度与字段。
- 父子切分：返回的是父块，content 偏长(~1000-1500+ 字)，可能带 chunkRole=parent。
- flat 平铺：返回的是普通块，content 偏短(~300-600 字)。
不改任何数据，只读。
"""
from __future__ import annotations

import sys
from pathlib import Path

EVAL_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(EVAL_ROOT))

try:
    from dotenv import load_dotenv
    load_dotenv(EVAL_ROOT / ".env")
except ImportError:
    pass

from lib.client import KnowledgeClient, kb_name_to_id  # noqa: E402

PROBE_QUERY = "云策企业版的功能和价格"


def main() -> None:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    c = KnowledgeClient()
    n2i = kb_name_to_id()
    if not n2i:
        sys.exit("KB_NAME_TO_ID 为空")
    lengths = []
    for name, kid in n2i.items():
        hits = c.search(kid, PROBE_QUERY, top_k=3)
        print(f"[{kid}] {name}  返回 {len(hits)} 条")
        for h in hits:
            content = h.get("content") or ""
            lengths.append(len(content))
            role = h.get("chunkRole") or h.get("chunk_role")
            print(f"    docId={h.get('docId')} chunkId={h.get('chunkId')} "
                  f"parentId={h.get('parentId')} role={role} 内容长度={len(content)}")
    if lengths:
        avg = sum(lengths) / len(lengths)
        print(f"\n命中块平均内容长度 = {avg:.0f} 字（{len(lengths)} 块）")
        print("→ 偏长(≳1000)更像【父子切分，返回父块】；偏短(≲600)更像【flat 平铺】。")


if __name__ == "__main__":
    main()
