#!/usr/bin/env python3
"""amplification.py — Jaeger-attributed cross-service amplification (LOAD-TESTING T4, ADR 0021 §6).

Samples the traces of ONE scenario's measurement window from the Jaeger API and computes the
ATTRIBUTED per-request outbound counts — which downstream, which operation:

    resolve         GET  usermgmt /internal/effective-role
    governed-scope  GET  usermgmt /internal/governed-targets
    tag             GET  usermgmt /internal/tag-definitions
    decide          POST opa /v1/data/{type}
    batch-eval      POST opa /v1/data/{type}/bulk
    compile         POST opa /v1/compile

and emits the expected-vs-measured table (JSON + markdown) beside the ADR-pinned per-scenario
bounds. Result semantics are REPORT-ONLY: a measured count above its pinned bound is a recorded
FINDING (exit 0 — the number is valid and load-bearing; 7.3 consumes it), never a fudged table.
The exits are VALIDITY-only:

    exit 0  evaluated (within bounds or findings recorded)
    exit 2  invalid window — too few traces (the MIN_TRACES floor: never extrapolate), no data,
            or an unreadable input

Counters (actuator metric diffs) are rejected by the ADR: aggregate-only, unattributable.

Offline (QA case U3): --input <file> reads a saved Jaeger API response instead of querying live;
scripts/load/tests/test-offline.sh runs the committed fixtures in tests/amplification-cases/.
"""

import argparse
import json
import statistics
import sys
import urllib.parse
import urllib.request

# The ADR 0021 §6 pinned per-scenario bounds — the library's design claims under proof.
# An op observed but not pinned here is reported as "unpinned" (recorded, no verdict).
EXPECTED = {
    "gate-overhead": {"resolve": 1, "decide": 1},
    "list-filter": {"resolve": 1, "compile": 1},
    "enrichment": {"resolve": 1, "batch-eval": 1},
}

OPS = ("resolve", "governed-scope", "tag", "decide", "batch-eval", "compile")


def classify(url: str):
    if "/internal/effective-role" in url:
        return "resolve"
    if "/internal/governed-targets" in url:
        return "governed-scope"
    if "/internal/tag-definitions" in url:
        return "tag"
    if "/v1/compile" in url:
        return "compile"
    if "/v1/data/" in url and url.rstrip("/").endswith("/bulk"):
        return "batch-eval"
    if "/v1/data/" in url:
        return "decide"
    return None


def per_trace_counts(trace: dict, service: str):
    """One request's outbound counts — or None if the trace isn't a single-request trace."""
    procs = trace["processes"]
    server_spans = 0
    counts = {op: 0 for op in OPS}
    for span in trace["spans"]:
        svc = procs[span["processID"]]["serviceName"]
        tags = {t["key"]: t["value"] for t in span["tags"]}
        kind = tags.get("span.kind", "")
        if svc == service and kind == "server":
            server_spans += 1
        if svc != service or kind != "client":
            continue
        op = classify(str(tags.get("url.full", tags.get("http.url", ""))))
        if op:
            counts[op] += 1
    return counts if server_spans == 1 else None


def fetch_traces(args):
    """Chunked fetch: many small windowed queries instead of one huge one.

    These traces can be ENORMOUS (a 20-row enriched list page is ~2,000 spans; a 100-row page far
    more), and a single high-limit /api/traces response over them is a 100MB+ payload that can
    kill the Jaeger query service mid-read (observed live). So the window is split into slices,
    each queried with a small limit, deduplicated by traceID, stopping once the sample target is
    comfortably above the floor. A failed slice is skipped (logged) — the MIN_TRACES floor still
    decides validity at the end.
    """
    if args.input:
        with open(args.input) as f:
            return json.load(f)["data"]

    target = args.min_traces + max(10, args.min_traces // 4)
    slices = 30
    span = max(1, (args.window_end - args.window_start) // slices)
    seen, traces = set(), []
    for i in range(slices):
        s = args.window_start + i * span
        e = min(args.window_end, s + span)
        if s >= args.window_end:
            break
        query = urllib.parse.urlencode({
            "service": args.service,
            "operation": args.operation,
            "start": s * 1_000_000,  # Jaeger wants microseconds
            "end": e * 1_000_000,
            "limit": 10,
        })
        try:
            with urllib.request.urlopen(f"{args.jaeger}/api/traces?{query}", timeout=60) as resp:
                batch = json.load(resp)["data"]
        except (OSError, ValueError) as exc:
            print(f"WARN: trace slice {i + 1}/{slices} failed ({exc}) — skipping", file=sys.stderr)
            continue
        for t in batch:
            tid = t.get("traceID")
            if tid and tid not in seen:
                seen.add(tid)
                traces.append(t)
        if len(traces) >= target:
            break
    return traces


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--scenario", required=True, choices=sorted(EXPECTED))
    p.add_argument("--service", default="catalog-management-service")
    p.add_argument("--operation", help="the server span name (required live)")
    p.add_argument("--window-start", type=int, help="epoch seconds (required live)")
    p.add_argument("--window-end", type=int, help="epoch seconds (required live)")
    p.add_argument("--jaeger", default="http://localhost:26686")
    p.add_argument("--limit", type=int, default=1500)
    p.add_argument("--min-traces", type=int, required=True,
                   help="the sample floor — below it the window is INVALID (exit 2), never extrapolated")
    p.add_argument("--input", help="offline: a saved Jaeger API response (U3)")
    p.add_argument("--out", help="output prefix (writes <out>.json and <out>.md)")
    args = p.parse_args()

    if not args.input and not (args.operation and args.window_start and args.window_end):
        print("ERROR: live mode needs --operation, --window-start, --window-end", file=sys.stderr)
        return 2

    try:
        traces = fetch_traces(args)
    except (OSError, ValueError, KeyError) as e:
        print(f"ERROR: cannot fetch/parse traces: {e}", file=sys.stderr)
        return 2

    samples = [c for c in (per_trace_counts(t, args.service) for t in traces) if c is not None]
    if len(samples) < args.min_traces:
        print(f"ERROR: only {len(samples)} attributable trace(s) in the window "
              f"(floor {args.min_traces}) — invalid sample, refusing to extrapolate.", file=sys.stderr)
        return 2

    expected = EXPECTED[args.scenario]
    rows, findings = [], []
    for op in OPS:
        values = [s[op] for s in samples]
        med, mx = statistics.median(values), max(values)
        if med == 0 and mx == 0 and op not in expected:
            continue
        bound = expected.get(op)
        if bound is None:
            verdict = "unpinned"
        elif mx <= bound:
            verdict = "within"
        else:
            verdict = "EXCEEDED"
            findings.append(f"{op}: measured median {med:g} (max {mx}) vs pinned bound {bound}")
        rows.append({"op": op, "expected": bound, "median": med, "max": mx, "verdict": verdict})

    result = {
        "scenario": args.scenario,
        "sample_traces": len(samples),
        "rows": rows,
        "within_bounds": not findings,
        "findings": findings,
    }

    md = [f"### Amplification — {args.scenario} ({len(samples)} traces attributed)",
          "", "| outbound op | pinned bound | measured (median) | measured (max) | verdict |",
          "|---|---|---|---|---|"]
    for r in rows:
        md.append(f"| {r['op']} | {r['expected'] if r['expected'] is not None else '—'} "
                  f"| {r['median']:g} | {r['max']} | {r['verdict']} |")
    if findings:
        md += ["", "**FINDINGS (report-only — 7.3 owns tuning):**"] + [f"- {f}" for f in findings]
    md_text = "\n".join(md)
    print(md_text)

    if args.out:
        with open(f"{args.out}.json", "w") as f:
            json.dump(result, f, indent=2)
            f.write("\n")
        with open(f"{args.out}.md", "w") as f:
            f.write(md_text + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
