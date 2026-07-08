#!/usr/bin/env python3
"""knee.py — evaluate one ceiling-ladder stage against the ADR 0021 §5 knee definition.

Reads a k6 --summary-export JSON and answers: did this stage sustain the knee?

    knee = p99 > 1 s  OR  > 1 % failed requests  OR  > 1 % dropped iterations

(k6's dropped_iterations means the offered arrival rate was not kept — in an open model that IS
saturation and must count. The 1 s marker is the ADR's reporting definition, not a quality
judgment; it is pinned — do not add criteria here.)

Output: one shell-parseable line on stdout, e.g.
    verdict=knee signal=latency p99_ms=1512.40 fail_pct=0.00 drop_pct=0.00
    verdict=pass signal=none p99_ms=310.20 fail_pct=0.00 drop_pct=0.00

Exit code is 0 whenever the summary could be evaluated (a knee is a valid DATUM, not an error);
2 on an unreadable/malformed summary (that is an invalid run — the caller must go red).

Offline-testable (QA case U2): scripts/load/tests/test-offline.sh runs the committed synthetic
summaries in scripts/load/tests/knee-cases/ through this file.
"""

import json
import sys

P99_LIMIT_MS = 1000.0
RATE_LIMIT = 0.01  # 1% — both for failed requests and dropped iterations


def evaluate(summary: dict) -> dict:
    metrics = summary["metrics"]
    p99_ms = float(metrics["http_req_duration"]["p(99)"])
    fail_rate = float(metrics.get("http_req_failed", {}).get("value", 0.0))

    completed = float(metrics.get("iterations", {}).get("count", 0.0))
    dropped = float(metrics.get("dropped_iterations", {}).get("count", 0.0))
    offered = completed + dropped
    drop_rate = (dropped / offered) if offered > 0 else 0.0

    # Signal precedence mirrors the ADR sentence order: latency, then errors, then drops.
    if p99_ms > P99_LIMIT_MS:
        verdict, signal = "knee", "latency"
    elif fail_rate > RATE_LIMIT:
        verdict, signal = "knee", "errors"
    elif drop_rate > RATE_LIMIT:
        verdict, signal = "knee", "drops"
    else:
        verdict, signal = "pass", "none"

    return {
        "verdict": verdict,
        "signal": signal,
        "p99_ms": round(p99_ms, 2),
        "fail_pct": round(fail_rate * 100, 2),
        "drop_pct": round(drop_rate * 100, 2),
    }


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: knee.py <k6-summary-export.json>", file=sys.stderr)
        return 2
    try:
        with open(sys.argv[1]) as f:
            result = evaluate(json.load(f))
    except (OSError, ValueError, KeyError, TypeError) as e:
        # An unreadable stage summary is an INVALID run, never a silent pass or a fake knee.
        print(f"ERROR: cannot evaluate {sys.argv[1]}: {e}", file=sys.stderr)
        return 2
    print(
        "verdict={verdict} signal={signal} p99_ms={p99_ms} "
        "fail_pct={fail_pct} drop_pct={drop_pct}".format(**result)
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
