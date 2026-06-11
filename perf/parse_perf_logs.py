"""perf/parse_perf_logs.py — 把服务日志里的 [PERF] 行聚合成"链路瀑布表"。

T1 已在各服务打了结构化日志（统一前缀 [PERF]）。本脚本扫描日志文件，
按 stage 聚合每段耗时的中位数/分位，产出 1.3 瀑布表的真实数据。

用法：
  python parse_perf_logs.py knowledge.log agent.log chat.log
  python parse_perf_logs.py logs/*.log --out results/waterfall.csv

识别的字段（来自 T1 埋点）：
  stage=recall   embedMs= denseMs= sparseMs=
  stage=search   recallMs= rerankMs=
  stage=rag      retrievalMs= llmMs= cacheHit=
  stage=llm-answer ms= tokens=
  stage=agent-llm  ms= tokens=
  stage=chat-rewrite-llm ms= tokens=

只统计成功的数值（-1 / None 跳过）。所有数字真实来自日志。
"""
import argparse
import glob
import os
import re

import common

# 抓 "key=value"（value 为数字，允许负号）
KV = re.compile(r"(\w+)=(-?\d+(?:\.\d+)?)")
PERF_LINE = re.compile(r"\[PERF\]\s+(.*)$")

# 关心的 (stage -> [字段])
STAGE_FIELDS = {
    "recall": ["embedMs", "denseMs", "sparseMs"],
    "search": ["recallMs", "rerankMs"],
    "rag": ["retrievalMs", "llmMs"],
    "llm-answer": ["ms"],
    "agent-llm": ["ms"],
    "chat-rewrite-llm": ["ms"],
}


def parse_files(paths):
    # samples[(stage, field)] = [values...]
    samples = {}
    for path in paths:
        if not os.path.exists(path):
            print(f"[perf] 跳过不存在的文件：{path}")
            continue
        with open(path, encoding="utf-8", errors="ignore") as f:
            for line in f:
                m = PERF_LINE.search(line)
                if not m:
                    continue
                kvs = dict((k, v) for k, v in KV.findall(m.group(1)))
                # stage 是字符串，不被数字正则抓到，单独取
                stage_m = re.search(r"stage=([\w-]+)", m.group(1))
                if not stage_m:
                    continue
                stage = stage_m.group(1)
                for field in STAGE_FIELDS.get(stage, []):
                    if field in kvs:
                        val = float(kvs[field])
                        if val < 0:  # -1 表示该段未执行
                            continue
                        samples.setdefault((stage, field), []).append(val)
    return samples


def main():
    ap = argparse.ArgumentParser(description="聚合 [PERF] 日志为瀑布表")
    ap.add_argument("logs", nargs="+", help="日志文件（支持通配）")
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    paths = []
    for p in args.logs:
        paths.extend(glob.glob(p))
    samples = parse_files(paths)

    rows = []
    for (stage, field), vals in sorted(samples.items()):
        s = common.summarize(vals)
        rows.append({"stage": stage, "field": field, "count": s.get("count"),
                     "p50": s.get("p50"), "p90": s.get("p90"), "p95": s.get("p95"),
                     "max": s.get("max")})
        print(f"  {stage:18s} {field:12s} n={s.get('count'):<4} p50={s.get('p50')} p95={s.get('p95')}")

    if not rows:
        print("[perf] 未在日志中找到 [PERF] 行。确认服务日志级别 INFO 且已用 T1 埋点版本。")
        return
    out = args.out or os.path.join(common.ensure_results_dir(), "waterfall.csv")
    common.write_csv(out, rows, fieldnames=["stage", "field", "count", "p50", "p90", "p95", "max"])


if __name__ == "__main__":
    main()
