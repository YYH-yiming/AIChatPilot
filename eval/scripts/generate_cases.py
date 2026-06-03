#!/usr/bin/env python
"""轨道2：基于冻结快照，用 LLM 为每个 chunk 生成多题型检索测试问题。

distant supervision 标注：问题来自某 chunk -> expectedDocId=该doc、expectedChunkIds=[该chunk]。
输出 datasets/generated.jsonl，需人工抽检后再并入 eval_set。

需要：datasets/chunks_snapshot.json（先跑 snapshot_chunks.py）+ LLM_API_KEY。
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

TYPES = ["FAQ", "配置", "操作", "概念", "追问", "同义", "缩写"]

SYSTEM_PROMPT = (
    "你是企业知识库的评测出题助手。基于给定的资料片段，生成若干条用于检索测试的中文问题。"
    "要求：问题口径多样（覆盖精确关键词、语义近义、口语化、追问省略等）；"
    "问题必须能由该资料片段回答；不要把答案原句直接抄进问题里（避免泄题）。"
    "只输出 JSON 数组，每个元素形如 "
    '{"question":"...","questionType":"FAQ|配置|操作|概念|追问|同义|缩写","goldAnswer":"一句话要点","needRewrite":true/false}。'
)


def build_user_prompt(kb_name: str, doc_title: str, content: str, k: int) -> str:
    return (
        f"知识库：{kb_name}\n文档：{doc_title}\n资料片段：\n{content}\n\n"
        f"请生成 {k} 条不同类型的问题，覆盖尽量多的题型。只输出 JSON 数组。"
    )


def parse_json_array(text: str) -> list:
    text = text.strip()
    start, end = text.find("["), text.rfind("]")
    if start == -1 or end == -1 or end < start:
        return []
    try:
        return json.loads(text[start : end + 1])
    except json.JSONDecodeError:
        return []


def main() -> None:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description="轨道2：LLM 生成标注用例")
    ap.add_argument("--per-chunk", type=int, default=2, help="每个 chunk 生成几条问题")
    ap.add_argument("--max-chunks-per-doc", type=int, default=5)
    ap.add_argument("--max-docs-per-kb", type=int, default=0, help="0=不限")
    ap.add_argument("--model", default=os.getenv("LLM_MODEL"))
    ap.add_argument("--temperature", type=float, default=0.7)
    ap.add_argument("--out", default=str(EVAL_ROOT / "datasets" / "generated.jsonl"))
    args = ap.parse_args()

    snap_path = EVAL_ROOT / "datasets" / "chunks_snapshot.json"
    if not snap_path.exists():
        sys.exit("缺少 chunks_snapshot.json，请先跑 snapshot_chunks.py")
    snapshot = json.loads(snap_path.read_text(encoding="utf-8"))

    try:
        from openai import OpenAI
    except ImportError:
        sys.exit("请先 pip install -r requirements.txt（openai）")
    if not os.getenv("LLM_API_KEY"):
        sys.exit("缺少 LLM_API_KEY")
    if not args.model:
        sys.exit("缺少 LLM_MODEL（--model 或 .env）")

    client = OpenAI(base_url=os.getenv("LLM_API_URL"), api_key=os.getenv("LLM_API_KEY"))

    cases: list[dict] = []
    counter = 0
    for kb_id, base in snapshot["bases"].items():
        kb_name = base.get("kbName", kb_id)
        docs = list(base["documents"].items())
        if args.max_docs_per_kb:
            docs = docs[: args.max_docs_per_kb]
        for doc_id, doc in docs:
            title = doc.get("fileName", doc_id)
            for chunk in doc["chunks"][: args.max_chunks_per_doc]:
                content = (chunk.get("content") or "").strip()
                if len(content) < 20:
                    continue
                try:
                    resp = client.chat.completions.create(
                        model=args.model,
                        temperature=args.temperature,
                        messages=[
                            {"role": "system", "content": SYSTEM_PROMPT},
                            {"role": "user", "content": build_user_prompt(kb_name, title, content, args.per_chunk)},
                        ],
                    )
                    items = parse_json_array(resp.choices[0].message.content or "")
                except Exception as e:  # noqa: BLE001
                    print(f"  生成失败 kb={kb_id} doc={doc_id} chunk={chunk.get('chunkId')}: {e}")
                    continue
                for it in items:
                    q = (it.get("question") or "").strip()
                    if not q:
                        continue
                    counter += 1
                    qtype = it.get("questionType") if it.get("questionType") in TYPES else "概念"
                    cases.append({
                        "caseId": f"gen-{counter:04d}",
                        "kbId": int(kb_id),
                        "kbName": kb_name,
                        "question": q,
                        "questionType": qtype,
                        "expectedDocIds": [int(doc_id)],
                        "expectedChunkIds": [chunk.get("chunkId")],
                        "expectedKeywords": [],
                        "goldAnswer": (it.get("goldAnswer") or "").strip() or None,
                        "needRewrite": bool(it.get("needRewrite", False)),
                        "shouldNotHitKbIds": [],
                        "source": "llm_gen",
                        "_docFile": title,
                        "_chunkIndex": chunk.get("chunkIndex"),
                    })

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8") as f:
        for c in cases:
            f.write(json.dumps(c, ensure_ascii=False) + "\n")
    print(f"生成完成：{len(cases)} 条 -> {out}")
    print("请人工抽检（删掉不自然/泄题样本）后再跑 build_eval_set.py 合并。")


if __name__ == "__main__":
    main()


