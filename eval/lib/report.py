"""结果导出：CSV + Markdown 表格。聚合逻辑放在各 run_*.py 里（用 pandas）。"""
from __future__ import annotations

import csv
from pathlib import Path
from typing import Sequence


def _fmt(v) -> str:
    if isinstance(v, float):
        return f"{v:.4f}"
    return "" if v is None else str(v)


def write_csv(rows: Sequence[dict], path: str | Path) -> None:
    rows = list(rows)
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    if not rows:
        path.write_text("", encoding="utf-8-sig")
        return
    # 取所有行的列并集，保持首行顺序优先
    cols: list[str] = list(rows[0].keys())
    for r in rows:
        for c in r.keys():
            if c not in cols:
                cols.append(c)
    with path.open("w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=cols)
        w.writeheader()
        w.writerows(rows)


def to_markdown_table(rows: Sequence[dict], columns: Sequence[str], headers: dict | None = None) -> str:
    headers = headers or {}
    head = "| " + " | ".join(headers.get(c, c) for c in columns) + " |"
    sep = "| " + " | ".join("---" for _ in columns) + " |"
    body = ["| " + " | ".join(_fmt(r.get(c)) for c in columns) + " |" for r in rows]
    return "\n".join([head, sep, *body])


def write_text(text: str, path: str | Path) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
