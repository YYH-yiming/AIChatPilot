#!/usr/bin/env python
"""冻结一次入库快照：把每个知识库的 doc / chunk 的真实自增 id 与内容拉下来存盘。

之后所有评测都对着这一份快照跑，避免重新入库导致 id 漂移、gold 对不上。

前置：5 个知识库的文档已上传并处理完成（parseStatus 成功、chunkCount>0）。
输出：datasets/chunks_snapshot.json
"""
from __future__ import annotations

import json
import sys
from datetime import datetime
from pathlib import Path

EVAL_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(EVAL_ROOT))

try:
    from dotenv import load_dotenv
    load_dotenv(EVAL_ROOT / ".env")
except ImportError:
    pass

from lib.client import KnowledgeClient, kb_name_to_id, ApiError  # noqa: E402


def main() -> None:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")

    client = KnowledgeClient()
    name_to_id = kb_name_to_id()

    # 优先用 .env 的映射；否则用接口列出的全部知识库
    if name_to_id:
        targets = [(name, kid) for name, kid in name_to_id.items()]
    else:
        bases = client.list_bases()
        targets = [(b.get("name", str(b.get("id"))), b.get("id")) for b in bases]
        print(f"未配置 KB_NAME_TO_ID，自动发现 {len(targets)} 个知识库")

    snapshot = {"generatedAt": datetime.now().isoformat(timespec="seconds"), "bases": {}}
    total_docs = total_chunks = 0

    for kb_name, kb_id in targets:
        try:
            docs = client.list_documents(kb_id)
        except ApiError as e:
            print(f"  跳过 {kb_name}(id={kb_id})：{e}")
            continue
        base_entry = {"kbName": kb_name, "documents": {}}
        for d in docs:
            doc_id = d.get("docId") or d.get("id")
            file_name = d.get("fileName") or d.get("name") or str(doc_id)
            chunks = client.list_chunks(kb_id, doc_id)
            base_entry["documents"][str(doc_id)] = {
                "fileName": file_name,
                "parseStatus": d.get("parseStatus"),
                "chunkCount": d.get("chunkCount"),
                "chunks": [
                    {
                        "chunkId": c.get("id"),
                        "chunkIndex": c.get("chunkIndex"),
                        "tokenCount": c.get("tokenCount"),
                        "content": c.get("content", ""),
                    }
                    for c in chunks
                ],
            }
            total_docs += 1
            total_chunks += len(chunks)
        snapshot["bases"][str(kb_id)] = base_entry

    out = EVAL_ROOT / "datasets" / "chunks_snapshot.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(snapshot, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"快照完成：{len(snapshot['bases'])} 库 / {total_docs} 文档 / {total_chunks} 切片 -> {out}")
    if total_chunks == 0:
        print("⚠️ 切片数为 0：确认文档已上传且异步处理完成（parseStatus 成功）。")


if __name__ == "__main__":
    main()
