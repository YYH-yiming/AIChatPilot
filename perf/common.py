"""perf/common.py — 压测脚本共享库。

仅依赖 httpx（随 eval/.venv 的 openai 传递安装）+ 标准库，可直接复用 eval 的虚拟环境：
    eval\\.venv\\Scripts\\Activate.ps1

所有数字由真实请求测得，脚本不预填、不编造。配置见 perf/config.example.env。
"""
import csv
import json
import os
import statistics
import time

# ---- 配置（环境变量优先，便于 run_baseline.ps1 注入）----
BASE_URL = os.environ.get("PERF_BASE_URL", "http://localhost:8080")
USERNAME = os.environ.get("PERF_USERNAME", "testforuser1")
PASSWORD = os.environ.get("PERF_PASSWORD", "testforuser1")
KB_ID = os.environ.get("PERF_KB_ID")  # 字符串或 None
QUERY_FILE = os.environ.get("PERF_QUERY_FILE")  # 默认回退到 eval 种子集

# 没有 query 文件时的兜底问题集（客服 FAQ 风格，仅兜底用；kb_id=None 时回退 PERF_KB_ID）
DEFAULT_QUERIES = [
    {"q": "怎么申请退货？", "kb_id": None},
    {"q": "退款一般多久到账？", "kb_id": None},
    {"q": "发票可以重开吗？", "kb_id": None},
    {"q": "保修期是多久？", "kb_id": None},
    {"q": "订单怎么查物流？", "kb_id": None},
    {"q": "支持七天无理由退货吗？", "kb_id": None},
    {"q": "如何联系人工客服？", "kb_id": None},
    {"q": "换货流程是什么？", "kb_id": None},
    {"q": "隐私政策在哪里看？", "kb_id": None},
    {"q": "签收后发现质量问题怎么办？", "kb_id": None},
]


def load_queries(path=None):
    """读问题集，返回 [{"q": 问题, "kb_id": int|None}]。

    默认用 eval/datasets/eval_set.jsonl —— 它每题带**真实 kbId(7-11)**，能让每条查询打到
    自己所属的知识库（否则统一打一个 KB，大半查询会 miss、走空检索兜底，测不到真实 RAG+LLM 路径）。
    kb_id 为 None 时由调用方回退到 PERF_KB_ID。
    """
    here = os.path.dirname(os.path.abspath(__file__))
    candidates = []
    path = path or QUERY_FILE
    if path:
        candidates.append(path)
    # 优先 eval_set.jsonl（真实 kbId），其次 seed_from_faq.jsonl（注意其 kbId 可能是占位 1）
    candidates.append(os.path.join(here, "..", "eval", "datasets", "eval_set.jsonl"))
    candidates.append(os.path.join(here, "..", "eval", "datasets", "seed_from_faq.jsonl"))
    for p in candidates:
        if p and os.path.exists(p):
            out = []
            with open(p, encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        obj = json.loads(line)
                        q = obj.get("question") or obj.get("query") or obj.get("q")
                        kb = obj.get("kbId") or obj.get("kb_id")
                        if q:
                            out.append({"q": q, "kb_id": kb})
                    except json.JSONDecodeError:
                        out.append({"q": line, "kb_id": None})
            if out:
                kbs = sorted({d["kb_id"] for d in out if d["kb_id"] is not None})
                print(f"[perf] 载入 {len(out)} 条查询：{p}（kbId 覆盖={kbs or '无,回退 PERF_KB_ID'}）")
                return out
    print("[perf] 未找到查询文件，使用内置兜底问题集（kb_id=None，回退 PERF_KB_ID）")
    return list(DEFAULT_QUERIES)


def resolve_kb(item, default_kb):
    """取查询自带 kb_id，没有则回退默认 kb。"""
    kb = item.get("kb_id") if isinstance(item, dict) else None
    return kb if kb is not None else default_kb


def auth_headers(token):
    return {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}


def login(client, base=None, username=None, password=None):
    """登录拿 token。返回 (token, userId, tenantId)。"""
    base = base or BASE_URL
    r = client.post(f"{base}/api/user/login",
                    json={"username": username or USERNAME, "password": password or PASSWORD})
    r.raise_for_status()
    data = (r.json() or {}).get("data") or {}
    token = data.get("token")
    if not token:
        raise RuntimeError(f"登录失败，未拿到 token：HTTP {r.status_code} {r.text[:300]}")
    return token, data.get("userId"), data.get("tenantId")


def create_session(client, token, mode="knowledge", kb_id=None, title="perf", base=None):
    """建会话，返回 sessionId。"""
    base = base or BASE_URL
    body = {"mode": mode, "title": title}
    if kb_id is not None:
        body["kbId"] = int(kb_id)
    r = client.post(f"{base}/api/chat/sessions", headers=auth_headers(token), json=body)
    r.raise_for_status()
    data = (r.json() or {}).get("data") or {}
    sid = data.get("sessionId") or data.get("id")
    if not sid:
        raise RuntimeError(f"建会话失败：HTTP {r.status_code} {r.text[:300]}")
    return sid


# ---- 统计 ----
def percentile(values, p):
    if not values:
        return None
    s = sorted(values)
    if len(s) == 1:
        return s[0]
    k = (len(s) - 1) * (p / 100.0)
    f = int(k)
    c = min(f + 1, len(s) - 1)
    if f == c:
        return s[f]
    return s[f] + (s[c] - s[f]) * (k - f)


def summarize(latencies_ms):
    """给一组毫秒延迟出 min/p50/p90/p95/p99/max/mean。"""
    vals = [v for v in latencies_ms if v is not None]
    if not vals:
        return {"count": 0}
    return {
        "count": len(vals),
        "min": round(min(vals), 1),
        "p50": round(percentile(vals, 50), 1),
        "p90": round(percentile(vals, 90), 1),
        "p95": round(percentile(vals, 95), 1),
        "p99": round(percentile(vals, 99), 1),
        "max": round(max(vals), 1),
        "mean": round(statistics.mean(vals), 1),
    }


# ---- 落盘 ----
def ensure_results_dir():
    here = os.path.dirname(os.path.abspath(__file__))
    d = os.path.join(here, "results")
    os.makedirs(d, exist_ok=True)
    return d


def write_csv(path, rows, fieldnames=None):
    if not rows:
        return
    fieldnames = fieldnames or list(rows[0].keys())
    with open(path, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=fieldnames)
        w.writeheader()
        for r in rows:
            w.writerow({k: r.get(k) for k in fieldnames})
    print(f"[perf] 写出 {len(rows)} 行 -> {path}")


def now_ms():
    return time.perf_counter() * 1000.0
