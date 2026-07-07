---
tags:
  - status/planned
  - type/project
  - area/infra
  - area/architecture
---

# LOAD-TESTING — autonomous implementation prompt

> **What this is.** A self-contained prompt for implementing the Phase-7.2 load-testing slice
> autonomously, ticket by ticket, with an architecture-review gate and a checkpoint after each
> ticket. The design and work list it refers to live alongside it in this folder.
>
> **Before you run it:** create the branch — `git checkout -b feature/void3110/load-testing` off a
> clean `main`. Confirm `git config --local user.email` is `void31102025@gmail.com`. Then paste the
> **PROMPT** section below to the agent.

---

## PROMPT

You are implementing **the Phase-7.2 load-testing harness** on branch `feature/void3110/load-testing`.

**The problem.** The library is approaching publish with **zero performance evidence** — an adopter's
first question about an authorization layer fronting every request is *what does it cost*, and there
is no harness to answer it. This slice adds a committed, one-command, re-runnable **k6 harness**
(`scripts/load/`, the postman-runner idiom) measuring four library hot paths through the real rig —
the single-decision **gate-overhead delta** (p50/95/99, guarded vs an `ENABLE_OPA=0` baseline behind
the identical gateway — the headline), the **partial-eval list ceiling** (an operationally-defined
knee), the **enrichment fan-out** and the **cross-service amplification ratio** (Jaeger-attributed,
expected-vs-measured), and **resilience under fault** (the B3 stub + `docker pause` on OPA, a fixed
three-phase timeline) — writing the publish-facing root **`PERFORMANCE.md`**. Every methodology fork
is pinned in ADR 0021. **Explicitly NOT in scope:** any tuning (7.3 owns tweaks — this slice only
measures), the demo UI / Keycloak login throughput / APISIX tuning, Prometheus/Grafana provisioning,
CI perf runs, and **any app/library/rego code change whatsoever** — the realm export (the `perf`
user), `scripts/**`, and docs are the entire surface.

Implement the core work directly. Do not delegate the implementation to a sub-agent. Sub-agents are
welcome for **read-only scouting** (e.g. "find how run-team-matrix.sh structures its preflight") and
for **log-noisy validation** (e.g. run a long measurement pass and report back only the summary) —
their findings come back to you; the code, tests, and docs are written in this loop.

### Read before you start (in order)

1. `LOAD-TESTING.md` (this folder's index) — what this slice delivers, the file glossary, the ticket
   status table, the critical path, conventions.
2. `00-DESIGN.md` — the mechanism (every named file in `scripts/load/`), the pinned forks, the
   **validity posture** (the harness's fail-closed analogue: no invalid number is ever recorded), and
   the scope boundary.
3. `01-DECOMPOSITION.md` — the 6 tickets, each with Goal / Deliverables / Acceptance /
   What-NOT-to-touch. **This is your work list.**
4. **The pinned decisions** — [[0021-load-testing-methodology|ADR 0021]] (all eight forks: k6
   host-run; the two-pass baseline flip; open-model rates/windows/REPS; fixtures + the `dddd…`/`perf`
   reservations; the knee definition; Jaeger-attributed amplification; stub + `docker pause` faults;
   report-only + validity gates + root `PERFORMANCE.md`, no CI) and
   [[0017-cross-service-http-resilience|ADR 0017]] (the B3 edges + the fault injector T5 reuses).
5. `10-QA-TEST-CASES.md` — the offline / smoke / official cases your work must satisfy.
6. **Context you will be checked against** in the review gate (step 5): the runner idioms in
   `scripts/postman/run-team-matrix.sh` (preflight, in-network token minting, fixture bootstrap,
   teardown-on-green, the directory preflight added 2026-07-07) and `run-isolation-matrix.sh` (the
   self-reset + psql idiom); the fixture-id registry in `scripts/postman/README.md`; the deploy flags
   + `generate_compose` env branches in `deploy.sh`; `infra/compose.resilience-stub.yaml` (the B3
   injector); the OTEL/Jaeger wiring in `deploy.sh` (`OTEL_TRACES_SAMPLER: always_on`).
7. Root `CLAUDE.md` — the **IP boundary** (clean-room: original names only) and the **commit
   identity** rule (`Void3110 <void31102025@gmail.com>`).
8. `infra/README.md` — the local rig, incl. the **in-network token caveat** (mint tokens inside the
   compose network; APISIX validates issuer `keycloak:8888`) and the flag recipes; note that a realm
   export change (the `perf` user) needs a **Keycloak container recreate** to re-import (and client
   `description` fields are capped at 255 chars — an oversize one aborts the whole realm import).
9. **Prime Mulch:** `ml prime opa-abac` (+ the directly-relevant records: the fixture-registry
   pattern for reserved subjects, the Keycloak realm-import failure, the deploy-only-builds-when-
   missing gotcha, the `autonomous-runs` retrospectives).

### Per-ticket loop (tickets T1 → T6, IN ORDER; T3/T4 are parallelizable after T2 but land as ordered commits)

For each ticket do ALL of the following, in order, and **STOP at the checkpoint before the next**:

1. **Prime for the files you're about to touch.** `ml prime --files <path>`, and re-read that ticket's
   section in `01-DECOMPOSITION.md`.

2. **Build the deliverables** exactly as `01-DECOMPOSITION.md` lists them — the exact scripts
   (`run-load.sh`, `scenarios/*.js`, `amplification.py`, `phases.py`), the realm-export addition, the
   registry rows. Match the surrounding runners' naming and idioms. **Clean-room:** no proprietary
   names anywhere.

3. **Write/extend the tests** for the ticket (the relevant U*/I*/E* cases from `10-QA-TEST-CASES.md`).
   This slice's ladder: **U = offline** (`bash -n`; the knee / attribution / phase functions against
   **committed synthetic fixtures** — no rig needed), **I = live smokes** at short rates/windows
   (`RATE=5 DURATION=15 WARMUP=5`), **E = the official run** (T6 only). No WireMock, no Testcontainers,
   no `opa test` — this slice ships no Java and no policy.

4. **Run the offline checks until green.** `bash -n` + the synthetic-fixture runs for the ticket's
   analysis functions. Fix-until-green.

5. **★ ARCHITECTURE REVIEW + REFACTOR — the gate before live validation.** Once the offline checks
   pass, do NOT advance yet. Run a focused self-review, then refactor and re-test:
   - **Validity check (this slice's load-bearing invariant): no invalid number can ever be recorded —
     every failure mode lands on a RED run** (preflight abort, the pod-state probe, k6 validity
     thresholds, the `MIN_TRACES` guard, the post-seed count assert, per-phase checks). State where
     each edge this ticket adds lands.
   - **Security check — name the widening that would matter** (the harness weakening the rig: a
     dedicated unguarded endpoint sneaking in, a flag flip that loosens the gateway between passes, a
     leftover faulted/unguarded rig after a run, the `perf` credential doing anything beyond the load
     team) — and state why it cannot happen (the flip reuses `deploy.sh` mechanisms only; restore is
     trap-on-exit; pod state is asserted, never trusted).
   - **Concurrency / idempotency check:** the harness only reads the services (no gated mutation);
     the runner itself must be **re-runnable** — seed idempotent (deterministic ids, count-asserted),
     teardown scoped to `dddd…`, restore idempotent. State that this holds for the ticket.
   - **Wiring check** — every seam this ticket adds (a mode, a knob, an analysis script, a registry
     row, a validity gate) has a **named consumer** and a test through its **non-happy path** (the
     abort fires, the mismatch reddens, the honest no-knee reports); zero call sites = the ticket is
     not done.
   - **Boundary / additivity check — zero app/library/rego diff**: `git diff main -- '*.java' '*.rego'
     opa-abac-* example-*` stays empty; the surface is `scripts/**`, the realm export, and docs. Name
     the byte-for-byte-unchanged surfaces (every existing runner, every deploy flag default).
   - **Module-layer separation** — the runner orchestrates; scenarios only generate load; the analysis
     scripts only read exports/APIs; `PERFORMANCE.md` only reports. No layer reaches across (a
     scenario never seeds; an analyzer never deploys).
   - **Pattern-reuse check** — the named idioms this must match, not reinvent: the postman runners'
     preflight/mint/seed/teardown shape, the fixture-registry discipline, the deploy-flag mechanism
     for the baseline flip, the B3 stub rig for supplier faults.
   - **SOLID / decomposition** — cohesive scripts (one job each), knee/phase/attribution logic in
     testable functions; anything to split/simplify?
   - **Apply** the refactoring the review surfaces, then **re-run the offline checks** to confirm green.
   - Write a short note of what the review found + what you refactored into `STATUS-0N.md`. If it
     found nothing substantive, say so explicitly — **do not invent churn.**

6. **Integration / e2e validation (MANDATORY for the relevant tickets).**
   - **T1:** the I1 smoke (preflight red on pod-state mismatch; seed count asserted; teardown/KEEP).
     **T2:** the I2 smoke `full` run (both passes, the delta block, rig ends guarded). **T3:** the I3
     mini-ladder. **T4:** the I4 smoke (the expected-vs-measured bounds hold — 1 batch eval per
     enriched page). **T5:** the I5 short-phase fault smokes (rig restored after every mode).
     Fix-until-green.
   - **T6 (the official run):** quiesce the laptop, bring the rig up
     (`ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2`, images fresh via
     `./deploy.sh build` first — `deploy.sh up` only builds a MISSING image), reseed, then the full
     `REPS=3` suite. Honor the in-network token caveat. No rego changes anywhere → no OPA restarts
     needed outside the fault mode itself. Fix-until-green.

7. **Update documentation (after each ticket).** Tick the ticket in the `LOAD-TESTING.md` status
   table; record real values/decisions in `STATUS-0N.md`. T6 writes root `PERFORMANCE.md`, links it
   from `README.md`, finalizes `scripts/load/README.md`, flips ADR 0021 to shipped, and moves the
   folder. Root/project `CLAUDE.md` only if a new build/run step matters (the `brew install k6`
   prerequisite qualifies — one line in the build/run section, added in T1).

8. **Mulch expertise check (after each ticket).** `ml prime`/skim, then record any genuine reusable
   insight (`ml record opa-abac --type <pattern|decision|failure|reference> …`) and `ml sync`.
   **Before `ml sync`, `git restore --staged .`** so the sync commit touches `.mulch/` **only** (the
   swept-staged trap). Skip recording only if nothing is non-obvious.

9. **Commit** — one focused commit on this branch (scripts + fixtures + docs + the `STATUS-0N.md`
   note together). Identity `Void3110 <void31102025@gmail.com>`. Conventional subject
   (`feat(load): …` for T1–T5, `docs(performance): …` for T6). A `Co-Authored-By: Claude` trailer is
   welcome.

10. **CHECKPOINT — STOP and report.** Summarize what shipped, paste the offline + smoke/official
    summary, **summarize the review findings + the refactoring you applied** (step 5), list docs
    updated, and note any open question you resolved. Then proceed to the next ticket. **Do not batch
    tickets without a checkpoint.**

### Permissions / autonomy granted (do these WITHOUT asking)

- Create/modify everything under `scripts/load/`, the fixture-registry rows in
  `scripts/postman/README.md`, the realm export (the `perf` user), root `PERFORMANCE.md` +
  `README.md`'s link + `scripts/load/README.md`, docs in this folder, and Mulch — all on this branch.
- Stand up / tear down / redeploy / reseed the local rig in every mode this slice needs
  (`./deploy.sh` with `ENABLE_OPA=0`, `ENABLE_RESILIENCE_STUB=1`, the guarded default; recreate
  Keycloak to import the realm change; `docker pause/unpause` the OPA container); rebuild images;
  reset the `dddd…` fixtures.
- Install nothing globally without saying so — `k6` via Homebrew is the one expected host dependency
  (check `command -v k6` first; if absent, `brew install k6`).
- Fix any issue your own validation reveals (script, smoke, orchestration, analysis). Iterate until
  green.
- Commit per ticket on this branch.

### Hard rules

- **Report at every checkpoint and continue.** Don't batch tickets.
- **The architecture-review + refactor step (5) is NOT optional and happens BEFORE live validation**
  — offline green → review → refactor → re-test → then smokes/official. Document what it found.
- **Fix-until-green within the ticket.** Only STOP mid-ticket if genuinely *blocked*: the same root
  cause survives ≥3 focused attempts, OR a design decision the docs don't cover is needed, OR a local
  prerequisite is unrecoverable.
- **Validity is the load-bearing invariant — no invalid number is ever recorded: every failure mode
  (rig state, load errors, thin traces, wrong fixtures) lands on a red run, never a silently wrong
  table row.**
- **Slice-specific invariants the agent must never trade away:** **zero app/library/rego code
  change** (the harness measures the system; it never modifies it); **the rig always ends guarded**
  (restore is trap-on-exit — no paused OPA, no stub role-source, no `ENABLE_OPA=0` left behind); the
  **gateway posture is identical across the two passes** (only `ENABLE_OPA` flips); **report-only**
  (no perf thresholds — k6 thresholds are validity gates only); **registry discipline** (only `dddd…`
  ids and the `perf` identity, both registered); the **knee/timeline/window definitions are
  ADR-pinned** — never improvised mid-run.
- **Clean-room IP boundary** — never introduce proprietary names, package names, comments, or source.
- **`ddl-auto: validate` must pass** — trivially: this slice changes no schema and no entity; a clean
  boot of the untouched services is the proof.
- **Do NOT push, open PRs, or touch `main`.** Local + this branch only. The maintainer pushes.

---

## Operator notes (not part of the prompt)

- **The headline tickets** — T2 (the guarded-vs-baseline delta: the number the publish story leads
  with) and T4 (the attributed amplification bounds: the caches-bound-the-chatter claim, proven).
- **The fail-closed edge to eyeball** — the restore path: a red run that leaves the rig unguarded
  (`ENABLE_OPA=0`), stub-wired, or with OPA paused is the harness's version of fail-open. The
  trap-on-exit restore in `run-load.sh` is the thing to review hard.
- **Standalone-value subset** — T1+T2: the runner, fixtures, identity, and the headline delta are a
  complete, useful artifact even if the window closes there.
- **Rig / e2e specifics** — in-network token minting (host-replayed is fine); Keycloak recreate for
  the `perf` user import; `./deploy.sh build` before the official run (up only builds MISSING
  images); the official run wants a quiesced laptop (no builds, no browser stress) and Docker Desktop
  resources noted in `PERFORMANCE.md`.
- **CI does not run the rig yet** — and per ADR 0021 §8 the perf suite is deliberately **never** a CI
  job; `PERFORMANCE.md` records the manual official run.
- **Context management** — if the window grows long mid-run, finish the ticket, stop at its
  checkpoint, and resume in a **fresh session** (the ticket status table + STATUS notes are the
  handoff); sub-agents are for scouting/validation only, never the implementation.
- **Workflow-as-artifact:** keep this prompt verbatim; the `STATUS-0N.md` notes record each ticket's
  outcome. Move the folder to `docs/to-do/implemented/` on ship.
