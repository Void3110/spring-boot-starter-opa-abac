// resilience.js — the three-phase fault scenario (LOAD-TESTING T5, ADR 0021 §7).
//
// T2's request shape (single id'd GET of the load catalog as `perf`) at RATE, held across the
// FIXED three-phase timeline: PHASE seconds healthy → PHASE faulted → PHASE recovery. The runner
// injects/clears the fault at the boundaries and records the real timestamps; this scenario just
// keeps the arrival rate steady and streams per-request points (`k6 --out json`) for phases.py
// to slice.
//
// Thresholds: denials are EXPECTED during the fault phase, so there is no all-200 gate here — the
// per-phase validity (typed fast denials, completed recovery) lives in phases.py. The one k6-side
// validity gate is dropped_iterations == 0: if the offered rate was not kept, the timeline itself
// is invalid and nothing is recorded.
import http from 'k6/http';

const RATE = Number(__ENV.RATE || 50);
const DURATION = Number(__ENV.DURATION || 180); // the runner passes 3 x PHASE
const GATEWAY = __ENV.GATEWAY || 'http://localhost:9085';
const TOKEN = __ENV.PERF_TOKEN;
const CATALOG_ID = __ENV.LOAD_CATALOG_ID;

export const options = {
  scenarios: {
    resilience: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: `${DURATION}s`,
      // The full pool is PRE-allocated: fault-phase requests are slow by design (the ~3s typed
      // denies while the dependency is out), so in-flight occupancy spikes at the fault onset —
      // and k6's lazy VU initialization would drop iterations exactly there, reddening the strict
      // dropped_iterations==0 gate over an artifact. Sized for RATE x ~5s of in-flight denials.
      preAllocatedVUs: Math.max(100, RATE * 5),
      maxVUs: Math.max(100, RATE * 5),
    },
  },
  thresholds: {
    dropped_iterations: ['count==0'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  maxRedirects: 0,
};

// No expectedStatuses() here: a fault-phase 403 is a correct fail-closed answer, not an "error" —
// phases.py judges each phase's status mix against the mode's expectations.

export default function () {
  http.get(`${GATEWAY}/api/v1/catalogs/${CATALOG_ID}`, {
    headers: { Authorization: `Bearer ${TOKEN}` },
    tags: { scenario: 'resilience' },
    timeout: '15s',
  });
}
