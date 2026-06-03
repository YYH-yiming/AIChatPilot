#!/usr/bin/env python
"""轨道1（离线）：从 docs/knowledgeDocs 的「## 用户常见问法」段抓取真实用户问法，
生成高可信度种子用例。不需要任何服务在运行。

输出：datasets/seed_from_faq.jsonl
每条用例的 kbId 用 KB_NAME_TO_ID 映射填入；expectedDocIds 离线拿不到（自增 id），
先留空并记录 _docFile / _docTitle / kbName，等 snapshot + merge 步骤补成真实 docId。
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

EVAL_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(EVAL_ROOT))

try:
    from dotenv import load_dotenv
    load_dotenv(EVAL_ROOT / ".env")
except ImportError:
    pass

from lib.client import kb_name_to_id  # noqa: E402

FAQ_HEADER_RE = re.compile(r"^#{2,3}\s*(?:用户)?常见问(?:法|题)\s*$")
HEADER_RE = re.compile(r"^#{1,6}\s+")
LIST_ITEM_RE = re.compile(r"^\s*[-*]\s+(.*\S)\s*$")
TITLE_RE = re.compile(r"^#\s+(.*\S)\s*$")
BACKTICK_RE = re.compile(r"`([^`]+)`")


def strip_kb_name(dir_name: str) -> str:
    """01_客户服务政策库 -> 客户服务政策库"""
    return re.sub(r"^\d+[_\-\s]*", "", dir_name)


def parse_doc(md_path: Path) -> tuple[str, list[str], list[str]]:
    """返回 (文档标题, 用户常见问法列表, 文档内反引号术语)。"""
    title = md_path.stem
    questions: list[str] = []
    keywords: set[str] = set()
    in_faq = False
    lines = md_path.read_text(encoding="utf-8").splitlines()
    for line in lines:
        m = TITLE_RE.match(line)
        if m and title == md_path.stem:
            title = m.group(1).strip()
        for term in BACKTICK_RE.findall(line):
            if 1 <= len(term) <= 12:
                keywords.add(term.strip())
        if FAQ_HEADER_RE.match(line):
            in_faq = True
            continue
        if in_faq:
            if HEADER_RE.match(line):  # 下一个标题，FAQ 段结束
                in_faq = False
                continue
            item = LIST_ITEM_RE.match(line)
            if item:
                questions.append(item.group(1).strip())
    return title, questions, sorted(keywords)


def main() -> None:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")  # 避免 Windows 控制台中文乱码
    ap = argparse.ArgumentParser(description="轨道1：从 markdown 抓取用户常见问法")
    ap.add_argument("--docs-dir", default=None, help="knowledgeDocs 目录，默认取 KNOWLEDGE_DOCS_DIR 或 ../docs/knowledgeDocs")
    ap.add_argument("--out", default=str(EVAL_ROOT / "datasets" / "seed_from_faq.jsonl"))
    args = ap.parse_args()

    import os
    docs_dir = Path(args.docs_dir or os.getenv("KNOWLEDGE_DOCS_DIR") or (EVAL_ROOT.parent / "docs" / "knowledgeDocs"))
    docs_dir = docs_dir if docs_dir.is_absolute() else (EVAL_ROOT / docs_dir).resolve()
    if not docs_dir.is_dir():
        sys.exit(f"找不到语料目录: {docs_dir}")

    name_to_id = kb_name_to_id()
    cases: list[dict] = []
    counter = 0
    for kb_dir in sorted(p for p in docs_dir.iterdir() if p.is_dir()):
        kb_name = strip_kb_name(kb_dir.name)
        kb_id = name_to_id.get(kb_name)
        for md_path in sorted(kb_dir.glob("*.md")):
            if md_path.name.lower() == "readme.md":
                continue
            title, questions, doc_keywords = parse_doc(md_path)
            rel = md_path.relative_to(docs_dir).as_posix()
            for q in questions:
                counter += 1
                kw = sorted(set(BACKTICK_RE.findall(q)) | {title})
                cases.append({
                    "caseId": f"faq-{counter:04d}",
                    "kbId": kb_id,
                    "kbName": kb_name,
                    "question": q,
                    "questionType": "口语化",
                    "expectedDocIds": [],
                    "expectedChunkIds": [],
                    "expectedKeywords": kw or doc_keywords[:3],
                    "goldAnswer": None,
                    "needRewrite": False,
                    "shouldNotHitKbIds": [],
                    "source": "faq_harvest",
                    "_docFile": md_path.name,
                    "_docTitle": title,
                    "_docPath": rel,
                })

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8") as f:
        for c in cases:
            f.write(json.dumps(c, ensure_ascii=False) + "\n")

    kbs = sorted({c["kbName"] for c in cases})
    print(f"抓取完成：{len(cases)} 条用例，覆盖 {len(kbs)} 个知识库 -> {out}")
    for kb in kbs:
        n = sum(1 for c in cases if c["kbName"] == kb)
        mapped = "(已映射kbId)" if name_to_id.get(kb) else "(kbId未映射,待snapshot补)"
        print(f"  - {kb}: {n} 条 {mapped}")


if __name__ == "__main__":
    main()
