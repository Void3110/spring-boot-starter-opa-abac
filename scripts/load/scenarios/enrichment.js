// enrichment.js — the fan-out page scenario (LOAD-TESTING T4, ADR 0021 §6).
//
// GET a perPage=100 category page WITH `_actions` as `perf` (guarded pass only) — the Phase-6
// enrichment fan-out under load. The boundedness claims under proof (via amplification.py, which
// attributes this scenario's traces): the page's affordance maps come from batch evaluation (not
// N×rows decide calls) and per-request chatter stays bounded. This scenario only generates the
// load; the attribution is the analyzer's job.
//
// Validity thresholds only (steady posture, like gate-overhead): any non-200, error, or dropped
// iteration exits non-zero and the runner records nothing.
import http from 'k6/http';
import { check } from 'k6';

const RATE = Number(__ENV.RATE || 50);
const DURATION = Number(__ENV.DURATION || 120);
const GATEWAY = __ENV.GATEWAY || 'http://localhost:9085';
const TOKEN = __ENV.PERF_TOKEN;
const CATALOG_ID = __ENV.LOAD_CATALOG_ID;

export const options = {
  scenarios: {
    enrichment: {
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
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  maxRedirects: 0,
};

http.setResponseCallback(http.expectedStatuses(200));

export default function () {
  const res = http.get(
    `${GATEWAY}/api/v1/catalogs/${CATALOG_ID}/categories?perPage=100`,
    { headers: { Authorization: `Bearer ${TOKEN}` }, tags: { scenario: 'enrichment' } },
  );
  check(res, {
    'status 200': (r) => r.status === 200,
    'page carries _actions': (r) => String(r.body).includes('"_actions"'),
  });
}
