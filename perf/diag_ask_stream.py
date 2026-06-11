"""perf/diag_ask_stream.py — 直连 knowledge /ask/stream 验证真流式（验证闸①）。

绕过网关，直接打 knowledge:8082 的新端点（与 chat→knowledge 的真实直连路径一致，
KNOWLEDGE_SERVICE_URL=http://localhost:8082）。逐事件打印 meta/token/done 的到达时刻，确认：
  - 首个 token 在 ~0.5-1s 到达（而非等整段生成完）；
  - token 逐段到达（多个 token 事件，时间分散）；
  - done 携带完整 answer。

用法：
  python perf/diag_ask_stream.py --kb-id 7 --query 怎么退钱
  python perf/diag_ask_stream.py --kb-id 8 --query 标准版多少钱
依赖：httpx（eval/.venv 自带）。所有数字真实测得。
"""
import argparse
import json
import os
import time

import httpx

import common

KNOWLEDGE_URL = os.environ.get("PERF_KNOWLEDGE_URL", "http://localhost:8082")


def main():
    ap = argparse.ArgumentParser(description="直连 knowledge /ask/stream 验证真流式")
    ap.add_argument("--kb-id", type=int, default=7)
    ap.add_argument("--query", default="怎么退钱")
    ap.add_argument("--top-k", type=int, default=5)
    args = ap.parse_args()

    with httpx.Client(timeout=180.0) as c:
        token, uid, tid = common.login(c)
        print(f"[diag] login userId={uid} tenantId={tid}; 直连 {KNOWLEDGE_URL}（绕过网关）")
        headers = {
            "X-User-Id": str(uid),
            "X-Tenant-Id": str(tid),
            "Content-Type": "application/json",
            "Accept": "text/event-stream",
        }
        url = f"{KNOWLEDGE_URL}/api/knowledge/bases/{args.kb_id}/ask/stream"

        t0 = time.perf_counter()
        meta_at = first_token_at = None
        token_count = 0
        answer_chars = 0
        buf = []
        event = "message"
        with c.stream("POST", url, headers=headers,
                      json={"query": args.query, "topK": args.top_k}) as resp:
            print(f"[diag] POST {url} -> HTTP {resp.status_code}")
            if resp.status_code != 200:
                print(resp.read()[:500])
                return
            for line in resp.iter_lines():
                if line == "":
                    if not buf:
                        continue
                    name, data = event, "\n".join(buf)
                    dt = (time.perf_counter() - t0) * 1000.0
                    if name == "meta":
                        meta_at = dt
                        try:
                            m = json.loads(data)
                            print(f"[{dt:8.1f}ms] meta grounded={m.get('grounded')} "
                                  f"refs={m.get('referenceCount')} model={m.get('model')}")
                        except Exception:
                            print(f"[{dt:8.1f}ms] meta(raw) {data[:200]}")
                    elif name == "token":
                        token_count += 1
                        if first_token_at is None:
                            first_token_at = dt
                        try:
                            answer_chars += len(json.loads(data).get("text", ""))
                        except Exception:
                            pass
                        if token_count <= 3 or token_count % 50 == 0:
                            print(f"[{dt:8.1f}ms] token #{token_count}: {data[:60]}")
                    elif name == "done":
                        try:
                            d = json.loads(data)
                            ans = d.get("answer") or ""
                            print(f"[{dt:8.1f}ms] done tokenUsed={d.get('tokenUsed')} answerLen={len(ans)}")
                            print(f"[diag] answer 预览: {ans[:200]}")
                        except Exception:
                            print(f"[{dt:8.1f}ms] done(raw) {data[:200]}")
                    elif name == "error":
                        print(f"[{dt:8.1f}ms] ERROR {data[:300]}")
                    terminal = name in ("done", "error")
                    buf, event = [], "message"
                    if terminal:
                        break  # 终止事件后停止读取，避免读到连接关闭报 incomplete chunked read
                elif line.startswith("event:"):
                    event = line[len("event:"):].strip()
                elif line.startswith("data:"):
                    buf.append(line[len("data:"):].strip())

        total = (time.perf_counter() - t0) * 1000.0
        print("\n[diag] 汇总：")
        print(f"  meta 到达 : {meta_at:.1f}ms" if meta_at is not None else "  meta     : 未收到")
        print(f"  首 token  : {first_token_at:.1f}ms" if first_token_at is not None else "  首 token  : 未收到")
        print(f"  token 事件: {token_count} 个，累计 {answer_chars} 字符")
        print(f"  总耗时    : {total:.1f}ms")
        print("\n[diag] 判读：")
        if first_token_at is not None and total > 0 and first_token_at < total * 0.5:
            print("  ✅ 首 token 远早于总耗时 → 真流式生效（TTFT 与 E2E 分叉）")
        elif first_token_at is not None:
            print("  ⚠️ 首 token 接近总耗时 → 可能仍被攒批/假流式，检查 stream 解析或上游 stream=true 是否生效")
        if token_count <= 1:
            print("  ⚠️ token 事件 ≤1 → 没有逐段流式：可能 SiliconFlow 未按 delta 返回、stream_options 被拒、或解析有误")
        else:
            print(f"  ✅ 收到 {token_count} 个 token 事件 → 逐段流式正常")


if __name__ == "__main__":
    main()
