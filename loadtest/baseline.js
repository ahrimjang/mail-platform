/* 부하테스트 베이스라인 (ROADMAP-scale ①):
   N명짜리 캠페인 1건을 생성하고 완료될 때까지 폴링하면서
   - campaign_create_ms : POST /api/campaigns 응답 시간 (동기 팬아웃 비용)
   - e2e_seconds        : 생성 → COMPLETED 전체 시간
   - drain_msgs_per_sec : 워커 드레인 처리량 (N / e2e)
   를 측정한다.

   사용법:  k6 run -e RECIPIENTS=5000 loadtest/baseline.js
   환경변수: BASE_URL(기본 http://localhost:8080), RECIPIENTS(기본 1000),
            EMAIL/PASSWORD(기본 loadtest@example.com / loadtest123),
            POLL_SECONDS(기본 2), MAX_MINUTES(기본 30)                       */
import http from "k6/http";
import { check, fail, sleep } from "k6";
import { Trend } from "k6/metrics";

const BASE = __ENV.BASE_URL || "http://localhost:8080";
const N = Number(__ENV.RECIPIENTS || 1000);
const EMAIL = __ENV.EMAIL || "loadtest@example.com";
const PASSWORD = __ENV.PASSWORD || "loadtest123";
const POLL = Number(__ENV.POLL_SECONDS || 2);
const MAX_MIN = Number(__ENV.MAX_MINUTES || 30);

export const options = {
  scenarios: {
    baseline: { executor: "per-vu-iterations", vus: 1, iterations: 1, maxDuration: `${MAX_MIN + 1}m` },
  },
};

const createMs = new Trend("campaign_create_ms");
const e2eSeconds = new Trend("e2e_seconds");
const drainRate = new Trend("drain_msgs_per_sec");

const JSON_HEADERS = { "Content-Type": "application/json" };

export function setup() {
  // 로그인, 계정이 없으면 가입
  let res = http.post(`${BASE}/api/auth/login`, JSON.stringify({ email: EMAIL, password: PASSWORD }), { headers: JSON_HEADERS });
  if (res.status !== 200) {
    res = http.post(`${BASE}/api/auth/signup`, JSON.stringify({ email: EMAIL, password: PASSWORD, displayName: "loadtest" }), { headers: JSON_HEADERS });
  }
  if (res.status !== 200 && res.status !== 201) fail(`auth failed: ${res.status} ${res.body}`);
  return { token: res.json("token") };
}

export default function (data) {
  const auth = { headers: { ...JSON_HEADERS, Authorization: `Bearer ${data.token}` } };
  const stamp = Date.now();
  const recipients = Array.from({ length: N }, (_, i) => `load-${stamp}-${i}@bench.local`);

  // 1) 캠페인 생성 — 응답 시간 = 동기 팬아웃(INSERT N행 + 큐 발행 N건) 비용
  const t0 = Date.now();
  const res = http.post(`${BASE}/api/campaigns`, JSON.stringify({
    subject: `부하테스트 ${N}명 (${new Date(stamp).toISOString()})`,
    body: "<p>안녕하세요 {{email}}</p><p><a href=\"https://example.com/bench\">벤치 링크</a></p>",
    recipients,
  }), { ...auth, timeout: "300s" });
  const createTook = Date.now() - t0;
  createMs.add(createTook);
  check(res, { "campaign created (201)": (r) => r.status === 201 }) || fail(`create failed: ${res.status} ${String(res.body).slice(0, 200)}`);
  const id = res.json("id");
  console.log(`campaign #${id} created: ${N} recipients, create=${createTook}ms`);

  // 2) 완료까지 폴링 — 드레인 진행을 관찰
  const deadline = t0 + MAX_MIN * 60 * 1000;
  let view = null;
  let lastLogged = 0;
  while (Date.now() < deadline) {
    sleep(POLL);
    const poll = http.get(`${BASE}/api/campaigns/${id}`, auth);
    if (poll.status !== 200) continue;
    view = poll.json();
    if (view.sent - lastLogged >= Math.max(500, N / 10)) {
      console.log(`  progress: sent=${view.sent}/${N} pending=${view.pending} failed=${view.failed}`);
      lastLogged = view.sent;
    }
    if (view.status === "COMPLETED") break;
  }
  if (!view || view.status !== "COMPLETED") fail(`campaign #${id} did not complete within ${MAX_MIN}m (last: ${JSON.stringify(view)})`);

  // 3) 지표 산출
  const e2e = (Date.now() - t0) / 1000;
  e2eSeconds.add(e2e);
  drainRate.add(N / e2e);
  console.log(`campaign #${id} COMPLETED: sent=${view.sent} failed=${view.failed} bounced=${view.bounced} | e2e=${e2e.toFixed(1)}s | drain=${(N / e2e).toFixed(1)} msg/s`);

  check(view, {
    "all delivered (sent == N)": (v) => v.sent === N,
    "no failures": (v) => v.failed === 0 && v.bounced === 0,
  });
}
