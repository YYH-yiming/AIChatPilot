"""perf/bench_rest.py — 非 SSE 端点的吞吐/延迟压测。

覆盖场景：
  chat-sync : POST /api/chat/sessions/{sid}/messages         (S3 非流式聊天)
  ask       : POST /api/knowledge/bases/{kbId}/ask            (S5 纯问答 / S7 缓存)
  search    : POST /api/knowledge/bases/{kbId}/search         (S4 纯检索，隔离 LLM)
  agent     : POST /api/agent/chat                            (S6 双 LLM 串行链路)

记录：每请求端到端延迟 + 状态码分布（含 429，用于 A2 限流有效性）+ RPS + 错误率。
用法：
  并发扫描：python bench_rest.py --endpoint ask --sweep 1,4,8,16,32 --requests-per-level 60
  缓存命中：python bench_rest.py --endpoint ask --concurrency 1 --requests 20 --same-query
  限流验证：python bench_rest.py --endpoint ask --concurrency 1 --requests 200   (限流开启时跑，看 429)

输出：results/rest_<endpoint>_summary.csv + results/rest_<endpoint>_per_request.csv
依赖：httpx（eval/.venv 自带）。所有数字真实测得。
"""
import argparse
import asyncio
import collections
import os

import httpx

import common


ENDPOINTS = ["chat-sync", "ask", "search", "agent"]


async def _create_session(client, token, kb):
    body = {"mode": "knowledge", "title": "perf-rest"}
    if kb is not None:
        body["kbId"] = int(kb)
    r = await client.post(f"{common.BASE_URL}/api/chat/sessions",
                          headers=common.auth_headers(token), json=body)
    r.raise_for_status()
    data = (r.json() or {}).get("data") or {}
    return data.get("sessionId") or data.get("id")


async def one_request(client, token, endpoint, item, default_kb):
    """跑一条请求。每条查询打到自己的 kbId（回退 default_kb）。
    chat-sync 的建会话在计时前完成，不计入延迟。"""
    q = item["q"] if isinstance(item, dict) else item
    kb = common.resolve_kb(item, default_kb)
    rec = {"ok": False, "status": None, "ms": None, "err": None, "kb_id": kb}

    sid = None
    try:
        if endpoint == "chat-sync":
            sid = await _create_session(client, token, kb)
        elif endpoint == "agent":
            sid = 999999  # agent 只用 sessionId 做短期记忆 key
    except Exception as ex:
        rec["err"] = f"prep: {ex}"
        return rec

    h = common.auth_headers(token)
    t0 = common.now_ms()
    try:
        if endpoint == "ask":
            r = await client.post(f"{common.BASE_URL}/api/knowledge/bases/{kb}/ask",
                                  headers=h, json={"query": q, "topK": 5})
        elif endpoint == "search":
            r = await client.post(f"{common.BASE_URL}/api/knowledge/bases/{kb}/search",
                                  headers=h, json={"query": q, "topK": 5})
        elif endpoint == "agent":
            r = await client.post(f"{common.BASE_URL}/api/agent/chat", headers=h,
                                  json={"query": q, "sessionId": sid,
                                        "kbId": int(kb) if kb is not None else None})
        else:  # chat-sync
            r = await client.post(f"{common.BASE_URL}/api/chat/sessions/{sid}/messages",
                                  headers=h, json={"content": q, "topK": 5})
        rec["ms"] = round(common.now_ms() - t0, 1)
        rec["status"] = r.status_code
        rec["ok"] = r.status_code == 200
        if r.status_code != 200:
            rec["err"] = (r.text or "")[:120]
    except Exception as ex:
        rec["ms"] = round(common.now_ms() - t0, 1)
        rec["err"] = str(ex)[:160]
    return rec


async def run_level(token, endpoint, default_kb, queries, concurrency, requests, timeout, same_query):
    limits = httpx.Limits(max_connections=concurrency + 5, max_keepalive_connections=concurrency + 5)
    records = []
    async with httpx.AsyncClient(timeout=httpx.Timeout(timeout), limits=limits) as client:
        sem = asyncio.Semaphore(concurrency)

        async def worker(i):
            async with sem:
                item = queries[0] if same_query else queries[i % len(queries)]
                return await one_request(client, token, endpoint, item, default_kb)

        wall0 = common.now_ms()
        records = await asyncio.gather(*[worker(i) for i in range(requests)])
        wall_ms = common.now_ms() - wall0

    ok = [r for r in records if r["ok"]]
    summ = common.summarize([r["ms"] for r in ok])
    status_dist = dict(collections.Counter(r["status"] for r in records))
    rps = round(len(ok) / (wall_ms / 1000.0), 2) if wall_ms > 0 else 0
    row = {
        "endpoint": endpoint, "concurrency": concurrency, "requests": requests,
        "ok": len(ok), "errors": len(records) - len(ok), "rps": rps,
        "wall_s": round(wall_ms / 1000.0, 1),
        "p50": summ.get("p50"), "p90": summ.get("p90"), "p95": summ.get("p95"),
        "p99": summ.get("p99"), "max": summ.get("max"),
        "status_dist": status_dist,
    }
    print(f"[perf] {endpoint} c={concurrency} ok={len(ok)}/{requests} rps={rps} "
          f"p50={row['p50']} p95={row['p95']} p99={row['p99']} status={status_dist}")
    return row, records


async def main_async(args):
    queries = common.load_queries()
    default_kb = args.kb_id or common.KB_ID
    have_per_q_kb = any(q.get("kb_id") for q in queries)
    if args.endpoint in ("ask", "search") and not default_kb and not have_per_q_kb:
        raise SystemExit("ask/search 需要带 kbId 的查询集(eval_set.jsonl) 或 --kb-id/PERF_KB_ID")
    results_dir = common.ensure_results_dir()

    with httpx.Client(timeout=30.0) as c:
        token, uid, tid = common.login(c)
    print(f"[perf] 登录成功 userId={uid} tenantId={tid}")

    levels = ([int(x) for x in args.sweep.split(",")] if args.sweep else [args.concurrency])
    summary_rows, all_records = [], []
    for c in levels:
        n = args.requests_per_level if (args.sweep and args.requests_per_level) else args.requests
        row, records = await run_level(token, args.endpoint, default_kb, queries, c, n, args.timeout, args.same_query)
        summary_rows.append(row)
        for r in records:
            r["concurrency"] = c
            r["endpoint"] = args.endpoint
        all_records.extend(records)

    common.write_csv(os.path.join(results_dir, f"rest_{args.endpoint}_summary.csv"), summary_rows,
                     fieldnames=["endpoint", "concurrency", "requests", "ok", "errors", "rps", "wall_s",
                                 "p50", "p90", "p95", "p99", "max", "status_dist"])
    common.write_csv(os.path.join(results_dir, f"rest_{args.endpoint}_per_request.csv"), all_records,
                     fieldnames=["concurrency", "endpoint", "kb_id", "ok", "status", "ms", "err"])


def main():
    ap = argparse.ArgumentParser(description="非 SSE 端点压测")
    ap.add_argument("--endpoint", required=True, choices=ENDPOINTS)
    ap.add_argument("--kb-id", default=None)
    ap.add_argument("--concurrency", type=int, default=1)
    ap.add_argument("--requests", type=int, default=30)
    ap.add_argument("--sweep", default=None, help="并发梯度，如 1,4,8,16,32")
    ap.add_argument("--requests-per-level", type=int, default=None)
    ap.add_argument("--same-query", action="store_true", help="全程同一 query（测缓存命中 S7）")
    ap.add_argument("--timeout", type=float, default=120.0)
    args = ap.parse_args()
    asyncio.run(main_async(args))


if __name__ == "__main__":
    main()
