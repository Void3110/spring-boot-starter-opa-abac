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
//     the knee function evaluates, so they must be recorded, not fatal. Since the gateway's OPA
//     plugin carries a BOUNDED timeout (Slice 7.3), a saturation-adjacent OPA stall surfaces as a
//     timeout-DENY (403) — so 401/403s are saturation DATA here too, feeding the knee's >1%-failed
//     signal (auth_failures stays a recorded counter for attribution). The broken-ACL-chain guard
//     is the seed-time canary probe, which lands red BEFORE any ladder stage; the one validity
//     gate kept is wrong_count==0 (a non-discriminating page is a wrong measurement subject).
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const RATE = Number(__ENV.RATE || 50);
const DURATION = Number(__ENV.DURATION || 120);
const GATEWAY = __ENV.GATEWAY || 'http://localhost:9085';
const TOKEN = __ENV.PERF_TOKEN;
const CATALOG_ID = __ENV.LOAD_CATALOG_ID;
const LADDER_STAGE = __ENV.LADDER_STAGE === '1';
// The authorized row count the residual MUST produce (the emea third of FIXTURE_ROWS): a page that
// stops discriminating is a wrong MEASUREMENT SUBJECT, not a saturation signal — red in every mode.
const EXPECTED_COUNT = Number(__ENV.EXPECTED_COUNT || 0);

const authFailures = new Counter('auth_failures');
const wrongCount = new Counter('wrong_count');

export const options = {
  scenarios: {
    list_filter: {
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
  thresholds: LADDER_STAGE
    ? {
        // Ceiling stage: drops/errors/latency — and, with the bounded gateway OPA timeout,
        // timeout-DENIES — are knee signals, not validity failures. The broken-chain guard is
        // the seed canary; only a non-discriminating residual is red here.
        wrong_count: ['count==0'],
      }
    : {
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
  const res = http.get(`${GATEWAY}/api/v1/catalogs/${CATALOG_ID}/categories`, {
    headers: { Authorization: `Bearer ${TOKEN}` },
    tags: { scenario: 'list-filter' },
  });
  if (res.status === 401 || res.status === 403) {
    authFailures.add(1);
  }
  if (res.status === 200 && EXPECTED_COUNT > 0) {
    // The envelope's `count` is the subject-relative authorized total — THE residual's cut.
    const count = Number(JSON.parse(String(res.body)).count);
    if (count !== EXPECTED_COUNT) {
      wrongCount.add(1);
    }
  }
  check(res, {
    'status 200': (r) => r.status === 200,
    'paged envelope': (r) => String(r.body).includes('"items"'),
  });
}
