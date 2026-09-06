#!/usr/bin/env python3
"""osv-sweep.py — the SECOND half of the dependency CVE sweep (see osv-resolved.init.gradle.kts).

Reads `RESOLVED <project> group:artifact:version` lines on stdin, queries OSV's Maven ecosystem in
batches of 500, and prints one HIT line per (coordinate, advisory) with the projects that resolve it.
Exit 0 = CLEAN, exit 1 = at least one advisory. Triage a HIT the way the sweep note does: is the
version OURS (libs.versions.toml) or BOM-managed; is the vulnerable code path REACHABLE here
(grep, measured, not assumed); override the BOM only if Boot stalls AND a reachable path appears.
Needs network (api.osv.dev) — a manual gate, deliberately not wired into CI.
"""
import json, sys, urllib.request, collections
coords = collections.defaultdict(set)
for line in sys.stdin:
    if line.startswith("RESOLVED "):
        _, proj, gav = line.split(maxsplit=2); coords[gav.strip()].add(proj)
gavs = sorted(coords)
print(f"{len(gavs)} distinct resolved coordinates across {len({p for s in coords.values() for p in s})} projects")
hits = []
for i in range(0, len(gavs), 500):
    batch = gavs[i:i+500]
    q = {"queries": [{"package": {"name": f"{g.rsplit(':',1)[0]}", "ecosystem": "Maven"}, "version": g.rsplit(':',1)[1]} for g in batch]}
    req = urllib.request.Request("https://api.osv.dev/v1/querybatch", data=json.dumps(q).encode(), headers={"Content-Type": "application/json"})
    res = json.load(urllib.request.urlopen(req, timeout=60))
    for g, r in zip(batch, res["results"]):
        for v in r.get("vulns", []) or []:
            hits.append((g, v["id"], sorted(coords[g])))
if not hits:
    print("CLEAN — no OSV advisory on any resolved coordinate"); sys.exit(0)
for g, vid, projs in hits:
    print(f"HIT {g}  {vid}  <- {', '.join(projs)}")
sys.exit(1)
