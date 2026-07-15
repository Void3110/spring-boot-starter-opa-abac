---
tags:
  - status/in-progress
  - type/research
  - area/build
---

# QUALITY-GATE-SONAR-BASELINE — version, profile & baseline triage

> Investigation for the local Sonar quality gate ([`.sonar-local/`](../../../../.sonar-local/README.md),
> adopted 2026-07-15 on `feature/void3110/sonar-local-gate`). **Largely executed 2026-07-15** — §1
> (version) and §2 (triage) are done; §3 (profile) and §4 (coverage) remain, plus the §3a S6474
> maintainer decision. Method context: [[AUTONOMOUS-IMPLEMENTATION-FLOW]] §9 (quality gates), Mulch
> domain `quality-gate-sonar` (holds the FP catalog + the S5998 lesson).

## Executed 2026-07-15 (summary)

The gate went live pinned to `26.3.0.120487`; this session **bumped it to `26.7.0.124771-community`**
(§1) and **triaged the whole baseline** (§2). Multi-agent triage (per-rule adjudicate → adversarial
verify → synthesize) over 43 rule-classes: **17 fix, 25 by-design-FP** (recorded to
`quality-gate-sonar`), the rest mixed. The adversarial-verify pass caught **S5998** — a first-pass FP
that was a *real* fail-closed escape: `HttpOpaClient.SAFE_PATH`'s recursive regex stack-overflows on a
long path, and the `StackOverflowError` (an `Error`) escaped the `catch(Exception)` deny handler →
uncaught 500. Fixed with a linear scan + length cap + regression test. Full-tree baseline
**355 → 1** (the lone survivor is §3a's S6474, left open by decision); the changed-files gate is CLEAN;
`./gradlew build` green. FP instances are marked false-positive in the local Sonar DB **and** their
classes recorded in Mulch (belt-and-suspenders: the marks die with `down -v`, the Mulch records don't).

## Why the ordering mattered

Version-first (§1 before §2) was load-bearing: 26.7.0's `Sonar way` grew 542→555 rules and **S8445
(×116 on 26.3.0) was renumbered to S8924 (×71)** — triaging on the old analyzer would have adjudicated
a rule that no longer exists.

## 1. Pick the analyzer version — ✅ DONE (26.7.0.124771-community)

The pin is **reproducibility-only**; this project answers to no external Sonar server. A newer
analyzer adds/retires rules, so the version decision precedes the triage.

- [x] Bumped `.sonar-local/docker-compose.yml` to `26.7.0.124771-community`; `down -v` + re-bootstrap
      (re-copied the new built-in `Sonar way`, now **555** Java rules, into `OPA-ABAC Local Java`).
- [x] Re-ran `--all`; recorded the new baseline (355; histogram + the S8445→S8924 renumber) in
      `quality-gate-sonar`.
- [ ] **Cadence still open**: re-evaluate the pin each planning phase (a bump is cheap but re-baselines;
      26.8+ will land). Not yet a fixed policy.

## 2. Triage the baseline — ✅ DONE (355 → 1)

Batched **by rule**, adjudicated with a multi-agent workflow (per-rule reader → adversarial-verify
skeptic on every gate-critical + every FP-on-a-bug/vuln → synthesis).

- [x] Every rule decided fix vs by-design-FP (mixed where sites split). **17 fix rules applied**
      (2 gate-critical: S5998 fail-closed SOE escape, S5841 vacuous test assertion; ~15 mechanical incl.
      the deferred-then-done S8924). **25 FP classes** recorded to `quality-gate-sonar` as a single
      catalog record + the S5998 lesson as a `failure` record.
- [x] FP instances **both** marked false-positive in the local Sonar DB (via admin API) **and** recorded
      as classes in Mulch — the marks die with `down -v`, the Mulch records survive, so a re-bootstrap
      re-judges from the catalog rather than re-flagging.
- [x] End state: `--all` = **CLEAN** (the last finding, S6474, is now won't-fix per the §3a
      accepted-risk decision); changed-files gate CLEAN; `./gradlew build` green.

## 3. Tighten the profile (optional, after triage)

`OPA-ABAC Local Java` is owned and editable. Once the baseline is clean, consider activating rules
beyond `Sonar way` (the built-in profile is deliberately conservative). If the profile diverges,
export it (`api/qualityprofiles/backup`) and **commit the XML** to `.sonar-local/` so the divergence
is version-controlled (until then, the pinned image's built-in is the reproducible baseline — no XML
needed).

## 3a. S6474 Gradle dependency verification — ✅ DECIDED (accepted-risk, 2026-07-15)

The triage surfaced **S6474** (an inbound supply-chain gap for a *published* security library): the repo
secures **outbound** artifacts (GPG-signed Maven Central publications) but pulls **inbound** deps +
Gradle plugins with no checksum/signature gate. Two legs, both now resolved:

- **Distribution pin — DONE (pure win, zero maintenance):** `distributionSha256Sum` added to
  `gradle/wrapper/gradle-wrapper.properties` (merged in PR #88). The wrapper now refuses a tampered
  Gradle distribution — the single highest-leverage integrity control.
- **Full `verification-metadata.xml` — NOT ADOPTED (accepted-risk decision):** rejected for this repo
  after weighing cost vs. gain:
  - **Cost is real and recurring.** ~201 resolved runtime deps + 22 catalog plugins; the graph includes
    **`net.java.dev.jna` (per-OS native artifacts)** and Testcontainers, and **CI runs `ubuntu-latest`
    while dev is macOS-aarch64** — so the metadata would need multi-classifier entries and would break
    one environment's build whenever a classifier is missing, and must be regenerated on every
    dependency/plugin bump.
  - **Residual risk is bounded.** Inbound resolution is `mavenCentral()` + the Gradle plugin portal over
    **TLS**, artifacts are **signed**, and the **Gradle distribution is now SHA-pinned**. For a
    single-maintainer public portfolio repo (no privileged deploy target, no secret exfil surface in the
    build), that posture is defensible; full per-artifact verification is enterprise-CI hygiene whose
    marginal gain here doesn't cover its maintenance + cross-platform-CI-break cost.
  - **Recorded, not silently suppressed.** The S6474 instance is marked won't-fix in the local Sonar DB
    with this rationale, and the decision is captured in Mulch (`quality-gate-sonar`). Re-evaluate if the
    repo ever gains a privileged CI deploy step or a second maintainer.

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
