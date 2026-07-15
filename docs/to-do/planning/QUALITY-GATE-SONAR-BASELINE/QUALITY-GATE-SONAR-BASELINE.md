---
tags:
  - status/planned
  - type/research
  - area/build
---

# QUALITY-GATE-SONAR-BASELINE — version, profile & baseline triage

> Investigation to-do for the local Sonar quality gate ([`.sonar-local/`](../../../../.sonar-local/README.md),
> adopted 2026-07-15 on `feature/void3110/sonar-local-gate`). Not yet a slice — this note scopes the
> investigation; if the triage turns out to be more than a few focused batches, run it through
> `/decompose` like any other slice. Method context: [[AUTONOMOUS-IMPLEMENTATION-FLOW]] §9
> (quality gates), Mulch domain `quality-gate-sonar`.

## Why now

The gate went live **findings-only, changed-files-scoped**, pinned to `26.3.0.120487-community` with
an owned copy of the built-in `Sonar way` Java profile. That was the fastest correct adoption, and it
deliberately deferred three decisions — each below. The adoption-time **full-tree baseline is 399
standing findings** (`./.sonar-local/sonar-local.sh --no-scan --all`): top rules `S8445 ×116`,
`S5778 ×67`, `S7467 ×42` (largely test-code rules), then `S1192`/`S5853`/`S5838`/`S125`/`S2160`/
`S6068`/`S1186` at ~10 each. The changed-files default keeps these from blocking tickets, but they
are invisible debt until triaged.

## 1. Pick the analyzer version (do this FIRST — it re-baselines everything)

The 26.3.0 pin is **reproducibility-only**; this project answers to no external Sonar server.
Newer community builds exist (latest observed on Docker Hub 2026-07: **`26.7.0.124771-community`**).
A newer analyzer adds/retires rules, so the version decision must precede the triage — otherwise the
triage is against a rule set we're about to replace.

- [ ] Bump `.sonar-local/docker-compose.yml` to the chosen tag; `down -v` + re-bootstrap (the
      bootstrap re-copies the *new* built-in `Sonar way` into `OPA-ABAC Local Java`).
- [ ] Re-run `--all`; record the new baseline count + rule histogram in `quality-gate-sonar`.
- [ ] Decide the upgrade cadence (e.g. re-evaluate each planning phase; a bump is cheap but
      re-baselines).

## 2. Triage the baseline (the bulk of the work)

Mirror of the IDP SONAR-SMELL-BACKLOG method: batch **by rule**, not by file — one rule class per
batch keeps each diff reviewable and the fix pattern consistent.

- [ ] For each rule (descending count): decide **fix** vs **by-design FP**.
      - Fixes: mechanical batches on a branch, `/deep-review` before merge (test-code rules like
        S5778/S8445 may allow bulk fixes; S1192/S3776-style smells need per-site judgment).
      - FPs: record each *class* (rule + why it's by-design here) in **`quality-gate-sonar`**, then
        mark the instances (won't-fix in the Sonar UI, or leave OPEN and rely on the Mulch record —
        decide which; won't-fix survives re-scans but lives in the container DB, the Mulch record
        survives `down -v`).
- [ ] End state: `--all` is either CLEAN or every remaining finding maps to a recorded FP class.

## 3. Tighten the profile (optional, after triage)

`OPA-ABAC Local Java` is owned and editable. Once the baseline is clean, consider activating rules
beyond `Sonar way` (the built-in profile is deliberately conservative). If the profile diverges,
export it (`api/qualityprofiles/backup`) and **commit the XML** to `.sonar-local/` so the divergence
is version-controlled (until then, the pinned image's built-in is the reproducible baseline — no XML
needed).

## 4. Coverage condition (separate decision, needs build work)

The gate today has no coverage floor: the scan runs `testClasses` only (no test execution, no
jacoco). Adding `new_coverage` to the gate needs a jacoco XML aggregate across modules (the IDP
`jacocoRootReport` pattern) and makes every scan pay the full test-suite cost. Evaluate whether the
loop wants that locally, or whether coverage stays a CI-side concern once the repo has hosted CI
analysis. If adopted, the FP/threshold context gets its own domain: `quality-gate-coverage`.

## Acceptance (investigation-level)

- Version pinned by decision, not inertia; cadence noted in `.sonar-local/README.md`.
- Baseline triaged to CLEAN-or-recorded-FP; the `quality-gate-sonar` domain holds every FP class.
- Profile divergence (if any) committed as XML; coverage decision recorded (even if "not now").
