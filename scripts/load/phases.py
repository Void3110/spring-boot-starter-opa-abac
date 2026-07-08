#!/usr/bin/env python3
"""phases.py — slice a resilience run's request stream into the three phases (LOAD-TESTING T5).

Reads the k6 `--out json` stream of one resilience run, buckets every request by the
runner-recorded REAL fault timestamps (healthy < fault-start <= fault < fault-end <= recovery),
and reports per phase: request counts by class (2xx success / 403 typed denial / other),
p50/95/99 of successes and of denials, the fail-closed latency during the fault, and the
time-to-recovery (the first sustained all-2xx window after the fault clears).

Per-phase VALIDITY (red run — exit 2 — otherwise), mode-aware:

    healthy phase (all modes)     all requests 2xx
    fault phase, mode=opa|down    >= 1 denial; every non-2xx is a TYPED 403 (no 5xx, no network
                                  errors); denial p99 under --deny-ceiling-ms (a deny at the
                                  ceiling is a HANG wearing a status code, not fail-closed)
    fault phase, mode=transient   no denials required (the guard's retries absorb the blips);
                                  every request 2xx or typed 403; nothing else
    recovery phase (all modes)    recovery COMPLETES: a sustained all-2xx window starts within
                                  the phase and holds to its end

Report-only FINDINGS (recorded, exit stays 0): a fault-phase denial p95 more than 3x the healthy
success p95 (slow-ish denies below the ceiling — the E4 "recorded as a 7.3 finding" marker).

Offline (QA case U4): committed synthetic streams in tests/phases-cases/ run through this file.
"""

import argparse
import json
import re
import statistics
import sys
from datetime import datetime

RECOVERY_WINDOW_S = 5.0
_TS_TRIM = re.compile(r"(\.\d{6})\d+")  # fromisoformat handles micros, not nanos


def parse_time(ts: str) -> float:
    return datetime.fromisoformat(_TS_TRIM.sub(r"\1", ts)).timestamp()


def load_points(path: str):
    """(epoch_seconds, duration_ms, status) per request, from the k6 JSON stream."""
    points = []
    with open(path) as f:
        for line in f:
            try:
                rec = json.loads(line)
            except ValueError:
                continue
            if rec.get("type") != "Point" or rec.get("metric") != "http_req_duration":
                continue
            data = rec["data"]
            tags = data.get("tags") or {}
            if tags.get("scenario") != "resilience":
                continue
            status = int(tags.get("status") or 0)
            points.append((parse_time(data["time"]), float(data["value"]), status))
    points.sort()
    return points


def pct(values, q):
    if not values:
        return None
    return round(statistics.quantiles(values, n=100, method="inclusive")[q - 1], 2) if len(values) > 1 \
        else round(values[0], 2)


def phase_stats(name, pts):
    succ = [d for _, d, s in pts if 200 <= s < 300]
    deny = [d for _, d, s in pts if s == 403]
    other = [(d, s) for _, d, s in pts if not (200 <= s < 300) and s != 403]
    return {
        "phase": name, "requests": len(pts),
        "success": len(succ), "denied": len(deny), "other": len(other),
        "success_p50": pct(succ, 50), "success_p95": pct(succ, 95), "success_p99": pct(succ, 99),
        "denied_p50": pct(deny, 50), "denied_p95": pct(deny, 95), "denied_p99": pct(deny, 99),
        "_other_statuses": sorted({s for _, s in other}),
    }


def time_to_recovery(points, fault_end: float):
    """Seconds from fault-end to the start of the first sustained all-2xx window, or None."""
    tail = [(t, s) for t, _, s in points if t >= fault_end]
    for i, (t, s) in enumerate(tail):
        if not (200 <= s < 300):
            continue
        window_ok, seen_end = True, t
        for t2, s2 in tail[i:]:
            if t2 - t > RECOVERY_WINDOW_S:
                break
            seen_end = t2
            if not (200 <= s2 < 300):
                window_ok = False
                break
        if window_ok and seen_end - t >= RECOVERY_WINDOW_S * 0.8:
            return round(t - fault_end, 2)
        # A window shorter than RECOVERY_WINDOW_S at the stream tail counts only if it reaches
        # the last point (the run ended healthy).
        if window_ok and seen_end == tail[-1][0]:
            return round(t - fault_end, 2)
    return None


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--stream", required=True, help="the k6 --out json NDJSON file")
    p.add_argument("--fault-start", type=float, required=True, help="epoch s (runner-recorded, REAL)")
    p.add_argument("--fault-end", type=float, required=True)
    p.add_argument("--mode", required=True, choices=("opa", "supplier-down", "supplier-transient"))
    p.add_argument("--deny-ceiling-ms", type=float, default=5000.0,
                   help="fault-phase denial p99 above this is a hang, not fail-closed (red)")
    p.add_argument("--out", help="output prefix (writes <out>.json and <out>.md)")
    args = p.parse_args()

    try:
        points = load_points(args.stream)
    except OSError as e:
        print(f"ERROR: cannot read the stream: {e}", file=sys.stderr)
        return 2
    if not points:
        print("ERROR: no resilience points in the stream — invalid run.", file=sys.stderr)
        return 2

    phases = {
        "healthy": [pt for pt in points if pt[0] < args.fault_start],
        "fault": [pt for pt in points if args.fault_start <= pt[0] < args.fault_end],
        "recovery": [pt for pt in points if pt[0] >= args.fault_end],
    }
    stats = {name: phase_stats(name, pts) for name, pts in phases.items()}
    ttr = time_to_recovery(points, args.fault_end)

    invalid, findings = [], []
    h, f = stats["healthy"], stats["fault"]
    for name, st in stats.items():
        if st["requests"] == 0:
            invalid.append(f"{name} phase saw no requests — the timeline is broken")
    if h["requests"] and (h["denied"] or h["other"]):
        invalid.append(f"healthy phase not clean: {h['denied']} denials, {h['other']} other "
                       f"(statuses {h['_other_statuses']})")
    if f["other"]:
        invalid.append(f"fault phase has UNTYPED failures: {f['other']} non-403 errors "
                       f"(statuses {f['_other_statuses']}) — fail-closed must answer 403")
    if args.mode in ("opa", "supplier-down"):
        if f["requests"] and f["denied"] == 0:
            invalid.append("fault phase saw no denials — was the fault actually injected?")
        if f["denied_p99"] is not None and f["denied_p99"] > args.deny_ceiling_ms:
            invalid.append(f"fault-phase denial p99 {f['denied_p99']}ms exceeds the "
                           f"{args.deny_ceiling_ms:g}ms ceiling — a hang, not a fast typed deny")
    if ttr is None:
        invalid.append("recovery did not complete — no sustained healthy window after the fault cleared")

    if (f["denied_p95"] is not None and h["success_p95"] is not None
            and f["denied_p95"] > 3 * h["success_p95"]
            and not any("ceiling" in i for i in invalid)):
        findings.append(f"slow-ish denies: fault-phase denial p95 {f['denied_p95']}ms vs healthy "
                        f"success p95 {h['success_p95']}ms (>3x) — a 7.3 finding")

    md = [f"### Resilience phases — mode {args.mode} "
          f"(fault {args.fault_start:.0f}..{args.fault_end:.0f})",
          "", "| phase | requests | 2xx | 403 | other | success p50/p95/p99 (ms) | denied p50/p95/p99 (ms) |",
          "|---|---|---|---|---|---|---|"]
    for name in ("healthy", "fault", "recovery"):
        st = stats[name]
        sp = "/".join("—" if v is None else f"{v:g}" for v in
                      (st["success_p50"], st["success_p95"], st["success_p99"]))
        dp = "/".join("—" if v is None else f"{v:g}" for v in
                      (st["denied_p50"], st["denied_p95"], st["denied_p99"]))
        md.append(f"| {name} | {st['requests']} | {st['success']} | {st['denied']} "
                  f"| {st['other']} | {sp} | {dp} |")
    md.append("")
    md.append(f"time-to-recovery: {'INCOMPLETE' if ttr is None else f'{ttr:g}s'}")
    if findings:
        md += ["", "**FINDINGS (report-only — 7.3 owns tuning):**"] + [f"- {x}" for x in findings]
    if invalid:
        md += ["", "**INVALID RUN:**"] + [f"- {x}" for x in invalid]
    md_text = "\n".join(md)
    print(md_text)

    result = {"mode": args.mode, "phases": [
        {k: v for k, v in stats[n].items() if not k.startswith("_")}
        for n in ("healthy", "fault", "recovery")],
        "time_to_recovery_s": ttr, "findings": findings, "invalid": invalid}
    if args.out:
        with open(f"{args.out}.json", "w") as fh:
            json.dump(result, fh, indent=2)
            fh.write("\n")
        with open(f"{args.out}.md", "w") as fh:
            fh.write(md_text + "\n")

    if invalid:
        print("\nERROR: per-phase validity failed — red run, nothing recorded as a result.",
              file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
