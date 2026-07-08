// list-filter.js — the partial-eval list scenario (LOAD-TESTING T3, ADR 0021 §5).
//
// GET the load catalog's category list as `perf` — the PE path end to end: role resolve on the
// governing catalog, OPA Compile, the residual (tag conjunct included: perf's role is tag-gated to
// region=emea, so the residual is a REAL SQL cut of ~1/3 of FIXTURE_ROWS) pushed into the paged
// query. Guarded pass only.
//
// Two threshold postures, selected by LADDER_STAGE:
//   - steady (default): validity gates like gate-overhead — all-200, zero errors, zero dropped
//     iterations. A violated gate exits non-zero and the runner records nothing.
//   - LADDER_STAGE=1 (ceiling mode): saturation signals — errors, drops, slow p99 — are the DATA
//     the knee function evaluates, so they must be recorded, not fatal. The one validity gate kept
//     is auth_failures==0: a 401/403 means the rig/ACL chain is broken, and that must land RED,
//     never masquerade as an instant knee.
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const RATE = Number(__ENV.RATE || 50);
const DURATION = Number(__ENV.DURATION || 120);
const GATEWAY = __ENV.GATEWAY || 'http://localhost:9085';
const TOKEN = __ENV.PERF_TOKEN;
const CATALOG_ID = __ENV.LOAD_CATALOG_ID;
const LADDER_STAGE = __ENV.LADDER_STAGE === '1';

const authFailures = new Counter('auth_failures');

export const options = {
  scenarios: {
    list_filter: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: `${DURATION}s`,
      preAllocatedVUs: Math.max(10, Math.ceil(RATE / 2)),
      maxVUs: Math.max(50, RATE * 3),
    },
  },
  thresholds: LADDER_STAGE
    ? {
        // Ceiling stage: drops/errors/latency are knee signals, not validity failures.
        auth_failures: ['count==0'],
      }
    : {
        http_req_failed: ['rate==0'],
        checks: ['rate==1'],
        dropped_iterations: ['count==0'],
        auth_failures: ['count==0'],
      },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  maxRedirects: 0,
};

http.setResponseCallback(http.expectedStatuses(200));

export default function () {
  const res = http.get(`${GATEWAY}/api/v1/catalogs/${CATALOG_ID}/categories`, {
    headers: { Authorization: `Bearer ${TOKEN}` },
    tags: { scenario: 'list-filter' },
  });
  if (res.status === 401 || res.status === 403) {
    authFailures.add(1);
  }
  check(res, {
    'status 200': (r) => r.status === 200,
    'paged envelope': (r) => String(r.body).includes('"items"'),
  });
}
