"""perf/diag_ask.py — 单次 /ask 诊断：判断检索到底有没有命中。

为什么需要它：SSE 冒烟 e2e 只有 ~30ms，疑似检索空（references=0）导致答案 LLM 根本没跑。
本脚本直接打 /api/knowledge/bases/{kbId}/ask，打印 grounded / referenceCount / 各引用 source，
据此判断是 KB 没建索引、还是 embedding/Milvus/ES 不可达。

用法：
  python perf/diag_ask.py --kb-id 7 --query 怎么退钱
  python perf/diag_ask.py --kb-id 8 --query 合同怎么签
依赖：httpx（eval/.venv 自带）。
"""
import argparse
import json

import httpx

import common


def main():
    ap = argparse.ArgumentParser(description="诊断单次 /ask 检索是否命中")
    ap.add_argument("--kb-id", type=int, default=7)
    ap.add_argument("--query", default="怎么退钱")
    ap.add_argument("--top-k", type=int, default=5)
    args = ap.parse_args()

    with httpx.Client(timeout=120.0) as c:
        token, uid, tid = common.login(c)
        print(f"[diag] login userId={uid} tenantId={tid}")
        r = c.post(f"{common.BASE_URL}/api/knowledge/bases/{args.kb_id}/ask",
                   headers=common.auth_headers(token),
                   json={"query": args.query, "topK": args.top_k})
        print(f"[diag] POST /api/knowledge/bases/{args.kb_id}/ask -> HTTP {r.status_code}")
        try:
            body = r.json()
        except Exception:
            print("[diag] 非 JSON 响应：", r.text[:1000])
            return
        data = body.get("data") if isinstance(body, dict) and "data" in body else body
        if not isinstance(data, dict):
            print("[diag] 响应体：", json.dumps(body, ensure_ascii=False)[:1000])
            return
        refs = data.get("references") or []
        summary = {
            "code": body.get("code") if isinstance(body, dict) else None,
            "grounded": data.get("grounded"),
            "referenceCount": data.get("referenceCount"),
            "model": data.get("model"),
            "answer_preview": (data.get("answer") or "")[:200],
            "ref_sources": [
                {"source": x.get("source"), "score": x.get("score"),
                 "docId": x.get("docId"), "chunkId": x.get("chunkId")}
                for x in refs[:5]
            ],
        }
        print(json.dumps(summary, ensure_ascii=False, indent=2))
        print("\n[diag] 判读：")
        if data.get("referenceCount"):
            print("  referenceCount>0 → 检索命中。若 e2e 仍很快，看 ref_sources 是否全是 'db'(=Milvus/ES都挂了走DB兜底)。")
        else:
            print("  referenceCount=0 → 检索为空：要么该 KB 没建索引，要么 embedding/Milvus/ES 不可达。")
            print("  下一步：确认这批 KB(7-11) 是否真的上传并完成了索引；查 knowledge 控制台有无 Milvus/ES 报错。")


if __name__ == "__main__":
    main()
