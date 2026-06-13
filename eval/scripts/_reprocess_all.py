#!/usr/bin/env python
"""把 KB_NAME_TO_ID 里所有知识库的全部文档重新处理（换切分策略后重灌），并轮询至完成。

用途：flat / parent-child 两臂之间切换切分策略(改 flag + 重启 knowledge)后，对同一批文档重灌。
reprocess 保留 docId（见 DocumentController：docId 不变），故 eval_set 的 docId 仍有效、无需重建。
注意：reprocess 会先删旧 chunk 再重灌，失败会清空该文档 chunk——务必确保 MinIO/Milvus/ES 在线。
"""
from __future__ import annotations

import sys
import time
from pathlib import Path

EVAL_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(EVAL_ROOT))

try:
    from dotenv import load_dotenv
    load_dotenv(EVAL_ROOT / ".env")
except ImportError:
    pass

from lib.client import KnowledgeClient, kb_name_to_id, ApiError  # noqa: E402

DONE = {2, 3}  # parseStatus: 2=成功, 3=失败；其余视为处理中


def collect_statuses(client: KnowledgeClient, name_to_id: dict) -> dict:
    statuses = {}
    for _name, kid in name_to_id.items():
        for d in client.list_documents(kid):
            did = d.get("docId") or d.get("id")
            statuses[(kid, did)] = d.get("parseStatus")
    return statuses


def main() -> None:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    client = KnowledgeClient()
    name_to_id = kb_name_to_id()
    if not name_to_id:
        sys.exit("KB_NAME_TO_ID 为空，先在 .env 配置目标知识库")

    # 1) 收集所有 (kbId, docId)
    targets = []
    for _name, kid in name_to_id.items():
        for d in client.list_documents(kid):
            did = d.get("docId") or d.get("id")
            targets.append((kid, did, d.get("fileName") or d.get("name")))
    print(f"将重处理 {len(targets)} 个文档（{len(name_to_id)} 个库）")

    # 2) 逐个触发 reprocess
    fired = 0
    for kid, did, fn in targets:
        try:
            client._post(f"/api/knowledge/bases/{kid}/documents/{did}/reprocess", {})
            fired += 1
        except (ApiError, Exception) as e:  # noqa: BLE001
            print(f"  触发失败 kb={kid} doc={did} {fn}: {e}")
    print(f"已触发 {fired}/{len(targets)} 个重处理，开始轮询(最多 20 分钟)…")

    # 3) 轮询至全部完成
    deadline, waited, interval = 60 * 20, 0, 6
    statuses = {}
    while waited < deadline:
        statuses = collect_statuses(client, name_to_id)
        pending = [k for k, v in statuses.items() if v not in DONE]
        ok = sum(1 for v in statuses.values() if v == 2)
        fail = sum(1 for v in statuses.values() if v == 3)
        print(f"  [{waited}s] 成功 {ok} / 失败 {fail} / 处理中 {len(pending)}")
        if not pending:
            break
        time.sleep(interval)
        waited += interval

    # 4) 汇总失败项
    failed = [k for k, v in statuses.items() if v == 3]
    if failed:
        print(f"⚠️ 有 {len(failed)} 个文档处理失败(parseStatus=3)，排查后可单独重试：")
        for kid, did in failed:
            print(f"    kb={kid} doc={did}")
    else:
        print("✅ 全部文档处理完成(parseStatus=2)")


if __name__ == "__main__":
    main()
