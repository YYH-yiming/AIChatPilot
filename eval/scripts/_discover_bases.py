#!/usr/bin/env python
"""列出当前账号下所有知识库及其文档（大语料 eval 前用：确认入库 + 拿 kbId/文件名）。

输出每个库的 id/name/文档数，以及每篇文档的 docId / parseStatus / chunkCount / fileName，
用于：1) 确认"已全部入库"且解析成功(status 成功、chunks>0)；2) 拿到真实 kbId 填 .env。
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

from lib.client import KnowledgeClient  # noqa: E402


def main() -> None:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    c = KnowledgeClient()
    bases = c.list_bases()
    print(f"共 {len(bases)} 个知识库\n")
    for b in bases:
        kid = b.get("id")
        name = b.get("name")
        try:
            docs = c.list_documents(kid)
        except Exception as e:  # noqa: BLE001
            print(f"[{kid}] {name}  (列文档失败: {e})")
            continue
        print(f"[{kid}] {name}  文档数={len(docs)}")
        for d in docs:
            did = d.get("docId") or d.get("id")
            fn = d.get("fileName") or d.get("name")
            st = d.get("parseStatus")
            cc = d.get("chunkCount")
            print(f"    docId={did}  status={st}  chunks={cc}  {fn}")
        print()


if __name__ == "__main__":
    main()
