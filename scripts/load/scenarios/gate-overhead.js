// gate-overhead.js — the headline scenario (LOAD-TESTING T2, ADR 0021 §2/§3).
//
// A single id'd GET of the load catalog as the `perf` identity, at a fixed open-model arrival
// rate. Runs IDENTICALLY in both passes of `run-load.sh full` — guarded (OPA_ABAC_ENABLED=true:
// resolve + decide per request) and baseline (false: the library gate is the only thing absent) —
// so the p50/95/99 delta between the two passes IS the library gate's cost.
//
// Thresholds are VALIDITY GATES ONLY (ADR 0021 §8): any non-200, any network error, or any
// dropped iteration (the offered rate was not kept — VU exhaustion) exits k6 non-zero and the
// runner records nothing. There are NO latency thresholds — report-only numbers.
import http from 'k6/http';
import { check } from 'k6';

const RATE = Number(__ENV.RATE || 50);
const DURATION = Number(__ENV.DURATION || 120);
const GATEWAY = __ENV.GATEWAY || 'http://localhost:9085';
const TOKEN = __ENV.PERF_TOKEN;
const CATALOG_ID = __ENV.LOAD_CATALOG_ID;

export const options = {
  scenarios: {
    gate_overhead: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: `${DURATION}s`,
      preAllocatedVUs: Math.max(10, Math.ceil(RATE / 2)),
      maxVUs: Math.max(50, RATE * 3),
    },
  },
  thresholds: {
    // Validity only — a violated gate exits non-zero and the runner aborts red.
    http_req_failed: ['rate==0'],
    checks: ['rate==1'],
    dropped_iterations: ['count==0'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  // A silent redirect-to-login must land red, never count as a fast 200.
  maxRedirects: 0,
};

// Strict: only a 200 is an expected response (3xx would otherwise pass http_req_failed).
http.setResponseCallback(http.expectedStatuses(200));

export default function () {
  const res = http.get(`${GATEWAY}/api/v1/catalogs/${CATALOG_ID}`, {
    headers: { Authorization: `Bearer ${TOKEN}` },
    tags: { scenario: 'gate-overhead' },
  });
  check(res, {
    'status 200': (r) => r.status === 200,
    'body is the load catalog': (r) => String(r.body).includes(CATALOG_ID),
  });
}
