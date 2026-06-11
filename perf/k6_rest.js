// perf/k6_rest.js — 非 SSE 端点的吞吐压测（k6，可选，用于比 Python 客户端更高并发的交叉验证）
//
// k6 不内置 SSE，故 SSE 用 perf/sse_ttft_client.py；此脚本只压非流式端点。
// 安装 k6：https://grafana.com/docs/k6/latest/set-up/install-k6/
//
// 运行示例（先确认 A1 已关限流，否则单账号会被 429 卡住）：
//   k6 run -e PERF_BASE_URL=http://localhost:8080 -e PERF_ENDPOINT=ask -e PERF_KB_ID=1 \
//          -e PERF_USERNAME=testforuser1 -e PERF_PASSWORD=testforuser1 \
//          --vus 16 --duration 2m perf/k6_rest.js
//
// 并发扫描：分别用 --vus 1/2/4/8/16/32 各跑一次，记录 http_req_duration p95/p99 与 http_reqs 速率。
import http from "k6/http";
import { check } from "k6";
import { Trend, Counter } from "k6/metrics";

const BASE = __ENV.PERF_BASE_URL || "http://localhost:8080";
const ENDPOINT = __ENV.PERF_ENDPOINT || "ask"; // ask | search | chat-sync | agent
const KB_ID = __ENV.PERF_KB_ID || "1";
const USERNAME = __ENV.PERF_USERNAME || "testforuser1";
const PASSWORD = __ENV.PERF_PASSWORD || "testforuser1";

const QUERIES = [
  "怎么申请退货？", "退款一般多久到账？", "发票可以重开吗？", "保修期是多久？",
  "订单怎么查物流？", "支持七天无理由退货吗？", "如何联系人工客服？", "换货流程是什么？",
];

const ttRest = new Trend("rest_latency_ms", true);
const rl429 = new Counter("rate_limited_429");

export const options = {
  // 默认温和阶梯；用 --vus/--duration 覆盖更直接
  scenarios: {
    sweep: {
      executor: "constant-vus",
      vus: __ENV.K6_VUS ? parseInt(__ENV.K6_VUS) : 8,
      duration: __ENV.K6_DURATION || "2m",
    },
  },
  thresholds: {
    // 仅作观测，不让阈值失败中断（真实数字以输出为准）
    "http_req_failed": ["rate<1.0"],
  },
};

export function setup() {
  const res = http.post(`${BASE}/api/user/login`, JSON.stringify({ username: USERNAME, password: PASSWORD }), {
    headers: { "Content-Type": "application/json" },
  });
  const token = res.json("data.token");
  if (!token) throw new Error(`登录失败: ${res.status} ${res.body}`);
  let sid = null;
  if (ENDPOINT === "chat-sync") {
    const s = http.post(`${BASE}/api/chat/sessions`, JSON.stringify({ mode: "knowledge", title: "perf-k6", kbId: parseInt(KB_ID) }), {
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    });
    sid = s.json("data.sessionId") || s.json("data.id");
  }
  return { token, sid };
}

export default function (data) {
  const q = QUERIES[Math.floor(Math.random() * QUERIES.length)];
  const headers = { "Content-Type": "application/json", Authorization: `Bearer ${data.token}` };
  let res;
  if (ENDPOINT === "ask") {
    res = http.post(`${BASE}/api/knowledge/bases/${KB_ID}/ask`, JSON.stringify({ query: q, topK: 5 }), { headers });
  } else if (ENDPOINT === "search") {
    res = http.post(`${BASE}/api/knowledge/bases/${KB_ID}/search`, JSON.stringify({ query: q, topK: 5 }), { headers });
  } else if (ENDPOINT === "agent") {
    res = http.post(`${BASE}/api/agent/chat`, JSON.stringify({ query: q, sessionId: 999999, kbId: parseInt(KB_ID) }), { headers });
  } else { // chat-sync
    res = http.post(`${BASE}/api/chat/sessions/${data.sid}/messages`, JSON.stringify({ content: q, topK: 5 }), { headers });
  }
  ttRest.add(res.timings.duration);
  if (res.status === 429) rl429.add(1);
  check(res, { "status 200": (r) => r.status === 200 });
}
