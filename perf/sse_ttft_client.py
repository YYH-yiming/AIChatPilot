"""perf/sse_ttft_client.py — SSE 流式 TTFT 压测客户端。

测什么：对 chat 的 SSE 端点
    POST /api/chat/sessions/{sessionId}/messages/stream
精确记录每条请求的：
  - ttft_start_ms  : 收到第一个 `start` 事件的耗时
  - ttft_meta_ms   : 收到第一个 `meta` 事件的耗时（=检索完成、开始生成的信号）。
                     仅真流式有 meta；假流式无该事件 → 留空。可用来展示「检索完成」与「首字」之间的思考耗时。
  - ttft_ms        : 收到**第一个答案内容事件**的耗时（=感知首字 TTFT）。
                     假流式是 `reply`（≈端到端）；真流式是第一个 `token`。两种模式都只认答案内容事件，
                     绝不把 `meta`（检索完成）误记成首字，保证改造前后口径一致、可直接对比。
  - e2e_ms         : 收到 `done` 或流结束的耗时（=端到端）

两种用法：
  单链路瀑布（S1，并发=1 多次取中位数）：
    python sse_ttft_client.py --mode knowledge --concurrency 1 --requests 30
  并发扫描（S2，找 TTFT/端到端 P95 拐点与 SSE 并发悬崖）：
    python sse_ttft_client.py --mode knowledge --sweep 1,2,4,8,16,32 --requests-per-level 40

每条请求一个**新会话**（默认 turns=1，首轮无改写 LLM，测纯 RAG 主路径）。
--turns N>1 时在同一会话连发 N 条，第 2 条起会触发多轮改写 LLM，用于测 B9。

输出：results/sse_<mode>_per_request.csv（逐条）+ results/sse_<mode>_summary.csv（各并发汇总）。
依赖：httpx（eval/.venv 自带）。所有数字真实测得。
"""
import argparse
import asyncio
import os

import httpx

import common


def parse_sse_block(lines):
    """把若干行解析成 (event_name, data_str)。SSE 以空行分隔事件块。"""
    event = "message"
    data_parts = []
    for ln in lines:
        if ln.startswith(":"):  # 注释/心跳，忽略
            continue
        if ln.startswith("event:"):
            event = ln[len("event:"):].strip()
        elif ln.startswith("data:"):
            data_parts.append(ln[len("data:"):].strip())
    return event, "\n".join(data_parts)


async def create_session_async(client, token, mode, kb_id):
    body = {"mode": mode, "title": "perf-sse"}
    if kb_id is not None:
        body["kbId"] = int(kb_id)
    r = await client.post(f"{common.BASE_URL}/api/chat/sessions",
                          headers=common.auth_headers(token), json=body)
    r.raise_for_status()
    data = (r.json() or {}).get("data") or {}
    sid = data.get("sessionId") or data.get("id")
    if not sid:
        raise RuntimeError(f"create session HTTP {r.status_code}: {r.text[:200]}")
    return sid


async def one_request(client, token, mode, kb_id, query, turns):
    """跑一条 SSE 请求，返回计时记录 dict。"""
    rec = {"mode": mode, "ok": False, "status": None, "err": None,
           "ttft_start_ms": None, "ttft_meta_ms": None, "ttft_ms": None, "e2e_ms": None, "events": 0}
    try:
        sid = await create_session_async(client, token, mode, kb_id)
    except Exception as ex:  # 建会话失败也要记
        rec["err"] = f"create_session: {ex}"
        return rec

    url = f"{common.BASE_URL}/api/chat/sessions/{sid}/messages/stream"
    headers = common.auth_headers(token)
    headers["Accept"] = "text/event-stream"

    for turn in range(max(turns, 1)):
        body = {"content": query, "topK": 5}
        t0 = common.now_ms()
        got_start = None
        got_meta = None
        got_content = None
        events = 0
        try:
            async with client.stream("POST", url, headers=headers, json=body) as resp:
                rec["status"] = resp.status_code
                if resp.status_code != 200:
                    txt = (await resp.aread())[:200]
                    rec["err"] = f"HTTP {resp.status_code}: {txt!r}"
                    return rec
                buf = []
                async for line in resp.aiter_lines():
                    if line == "":  # 事件块结束
                        if not buf:
                            continue
                        name, data = parse_sse_block(buf)
                        buf = []
                        events += 1
                        now = common.now_ms()
                        if name == "error":
                            rec["err"] = f"sse-error: {data[:200]}"
                            break
                        if name == "start":
                            if got_start is None:
                                got_start = now - t0
                        elif name == "meta":
                            if got_meta is None:
                                got_meta = now - t0
                        elif name in ("token", "reply") and got_content is None:
                            got_content = now - t0  # 第一个答案内容事件 = 感知首字 TTFT（忽略 start/meta）
                        if name == "done":
                            break
                    else:
                        buf.append(line)
            e2e = common.now_ms() - t0
        except Exception as ex:
            rec["err"] = f"stream: {ex}"
            return rec

        # 只记录最后一轮（多轮时前几轮用于铺垫历史）
        rec["ttft_start_ms"] = round(got_start, 1) if got_start is not None else None
        rec["ttft_meta_ms"] = round(got_meta, 1) if got_meta is not None else None
        rec["ttft_ms"] = round(got_content, 1) if got_content is not None else None
        rec["e2e_ms"] = round(e2e, 1)
        rec["events"] = events
    rec["ok"] = rec["err"] is None and rec["ttft_ms"] is not None
    return rec


async def run_level(token, mode, kb_id, queries, concurrency, requests, turns, timeout):
    sem = asyncio.Semaphore(concurrency)
    records = []
    limits = httpx.Limits(max_connections=concurrency + 5, max_keepalive_connections=concurrency + 5)
    async with httpx.AsyncClient(timeout=httpx.Timeout(timeout), limits=limits, http2=False) as client:
        async def worker(i):
            async with sem:
                item = queries[i % len(queries)]
                kb = common.resolve_kb(item, kb_id)
                return await one_request(client, token, mode, kb, item["q"], turns)

        wall0 = common.now_ms()
        results = await asyncio.gather(*[worker(i) for i in range(requests)])
        wall_ms = common.now_ms() - wall0
    records.extend(results)
    ok = [r for r in records if r["ok"]]
    summ_ttft = common.summarize([r["ttft_ms"] for r in ok])
    summ_e2e = common.summarize([r["e2e_ms"] for r in ok])
    meta_vals = [r["ttft_meta_ms"] for r in ok if r["ttft_meta_ms"] is not None]
    summ_meta = common.summarize(meta_vals) if meta_vals else {}
    rps = round(len(ok) / (wall_ms / 1000.0), 2) if wall_ms > 0 else 0
    row = {
        "mode": mode, "concurrency": concurrency, "requests": requests,
        "ok": len(ok), "errors": len(records) - len(ok),
        "rps": rps, "wall_s": round(wall_ms / 1000.0, 1),
        "meta_p50": summ_meta.get("p50"), "meta_p95": summ_meta.get("p95"),
        "ttft_p50": summ_ttft.get("p50"), "ttft_p95": summ_ttft.get("p95"), "ttft_p99": summ_ttft.get("p99"),
        "e2e_p50": summ_e2e.get("p50"), "e2e_p95": summ_e2e.get("p95"), "e2e_p99": summ_e2e.get("p99"),
    }
    print(f"[perf] mode={mode} c={concurrency} ok={len(ok)}/{requests} rps={rps} "
          f"meta p50={row['meta_p50']} | TTFT p50={row['ttft_p50']} p95={row['ttft_p95']} "
          f"| E2E p50={row['e2e_p50']} p95={row['e2e_p95']}")
    return row, records


async def main_async(args):
    queries = common.load_queries()
    kb_id = args.kb_id or common.KB_ID
    results_dir = common.ensure_results_dir()

    with httpx.Client(timeout=30.0) as c:
        token, uid, tid = common.login(c)
    print(f"[perf] 登录成功 userId={uid} tenantId={tid}")

    levels = ([int(x) for x in args.sweep.split(",")] if args.sweep else [args.concurrency])
    per_level = args.requests_per_level or args.requests

    summary_rows = []
    all_records = []
    for c in levels:
        n = per_level if args.sweep else args.requests
        row, records = await run_level(token, args.mode, kb_id, queries, c, n, args.turns, args.timeout)
        summary_rows.append(row)
        for r in records:
            r["concurrency"] = c
        all_records.extend(records)

    common.write_csv(os.path.join(results_dir, f"sse_{args.mode}_summary.csv"), summary_rows)
    common.write_csv(os.path.join(results_dir, f"sse_{args.mode}_per_request.csv"), all_records,
                     fieldnames=["concurrency", "mode", "ok", "status", "ttft_start_ms", "ttft_meta_ms", "ttft_ms", "e2e_ms", "events", "err"])


def main():
    ap = argparse.ArgumentParser(description="SSE TTFT 压测客户端")
    ap.add_argument("--mode", default="knowledge", choices=["knowledge", "agent"])
    ap.add_argument("--kb-id", default=None, help="知识库 id（覆盖 PERF_KB_ID）")
    ap.add_argument("--concurrency", type=int, default=1)
    ap.add_argument("--requests", type=int, default=30, help="单并发档总请求数")
    ap.add_argument("--sweep", default=None, help="并发梯度，如 1,2,4,8,16,32（设置则忽略 --concurrency）")
    ap.add_argument("--requests-per-level", type=int, default=None, help="sweep 模式每档请求数")
    ap.add_argument("--turns", type=int, default=1, help="同一会话连发轮数；>1 触发多轮改写 LLM")
    ap.add_argument("--timeout", type=float, default=180.0, help="单请求超时秒")
    args = ap.parse_args()
    asyncio.run(main_async(args))


if __name__ == "__main__":
    main()
