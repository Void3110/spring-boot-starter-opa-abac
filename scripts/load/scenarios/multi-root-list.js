// multi-root-list.js — the multi-root catalogs-list scenario (RESOLVE-COALESCING T1, ADR 0024).
//
// GET the caller's catalogs page as `perf` — the page shape the 7.2 harness never measured: every
// row is its OWN governing root (a catalog has no ancestors), so per-row role resolution cannot be
// collapsed by any duplicate-target memo. Pre-7.3 this page costs M distinct sequential
// cross-service resolves (the disprovable "before", QA P3); post-7.3 the batch `lookupAll` makes it
// one wire exchange. This scenario only generates the load; amplification.py attributes the counts.
//
// Steady posture only (no ladder form): validity gates like list-filter — all-200, zero errors,
// zero dropped iterations, zero auth failures, and the page must be EXACTLY the seeded M multi-root
// rows, every row carrying its `_actions` map. A page that is short, padded with foreign rows, or
// silently degraded (omitted `_actions` = the advice's failure rung) is a wrong MEASUREMENT
// SUBJECT — red in every mode, never a recorded number.
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const RATE = Number(__ENV.RATE || 5);
const DURATION = Number(__ENV.DURATION || 120);
const GATEWAY = __ENV.GATEWAY || 'http://localhost:9085';
const TOKEN = __ENV.PERF_TOKEN;
// The seeded multi-root catalog count M — both the page size requested and the authorized `count`
// the envelope must answer (perf's memberships are exactly the M multi-root teams).
const EXPECTED_COUNT = Number(__ENV.EXPECTED_COUNT || 0);

const authFailures = new Counter('auth_failures');
const wrongCount = new Counter('wrong_count');

export const options = {
  scenarios: {
    multi_root_list: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: `${DURATION}s`,
      // Fully pre-allocated: k6's lazy VU initialization drops iterations under transient
      // latency spikes (VU-init lag, not true saturation) — drops must MEAN saturation.
      preAllocatedVUs: Math.max(50, RATE * 3),
      maxVUs: Math.max(50, RATE * 3),
    },
  },
  thresholds: {
    http_req_failed: ['rate==0'],
    checks: ['rate==1'],
    dropped_iterations: ['count==0'],
    auth_failures: ['count==0'],
    wrong_count: ['count==0'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  maxRedirects: 0,
};

http.setResponseCallback(http.expectedStatuses(200));

export default function () {
  const res = http.get(`${GATEWAY}/api/v1/catalogs?perPage=${EXPECTED_COUNT}`, {
    headers: { Authorization: `Bearer ${TOKEN}` },
    tags: { scenario: 'multi-root-list' },
  });
  if (res.status === 401 || res.status === 403) {
    authFailures.add(1);
  }
  let page = null;
  if (res.status === 200) {
    page = JSON.parse(String(res.body));
    // The envelope's `count` is the subject-relative authorized total — perf's membership scope.
    if (EXPECTED_COUNT > 0 && Number(page.count) !== EXPECTED_COUNT) {
      wrongCount.add(1);
    }
  }
  check(res, {
    'status 200': (r) => r.status === 200,
    'full multi-root page': () =>
      page !== null && Array.isArray(page.items) && page.items.length === EXPECTED_COUNT,
    // Every row enriched: an omitted `_actions` map is the advice's degrade rung firing —
    // a silently smaller resolve fan-out, i.e. a wrong measurement subject.
    'every row carries _actions': () =>
      page !== null && page.items.every((it) => it._actions !== undefined),
  });
}
