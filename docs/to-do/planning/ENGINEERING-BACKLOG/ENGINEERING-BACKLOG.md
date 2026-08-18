---
tags:
  - status/active
  - type/project
  - area/methodology
---

# Engineering backlog — high-value / low-effort

> Small, independent items that are worth doing but do not deserve a slice each. Opened
> **2026-08-18** at the SPA-CHALLENGE-UX close-out. Ordered by **value ÷ effort**, not by
> severity — everything here is Low or cosmetic in isolation; the case for the top items is that
> they **mechanise defect classes that cost three adversarial review rounds to find by hand**.
>
> Take from the top. Each item is self-contained: no item blocks another.
>
> **Status 2026-08-18: items 1–8 are SHIPPED.** Item 9 remains, blocked on an upstream release.
> Each shipped item carries the correction its own estimate needed — read those before trusting a
> future backlog entry's effort figure: five of the eight were wrong in a way that mattered.

## Why this list exists

The SPA-CHALLENGE-UX layer-3 review ran three adversarial rounds and produced **27 findings, 0
Critical, none in the authorization logic**. Every one landed in the **verification and scaffolding
layer** — Postman cells, shell runners, CI wiring, a gate script, docs. Rounds 2 and 3 found defects
*exclusively in the previous round's fixes*.

The lesson is not "review harder". It is that **this repo has no static analysis over its
verification layer at all**: the Sonar gate scans only changed `.java` files, so 14 of the first 16
findings were in file types nothing checks. Multi-agent review was filling that gap at roughly 1.8M
tokens a round. The top items below move that work to machines.

**Measured, not assumed:** `shellcheck` was tested against the two shell defects this review found
(a `set -e`-masked guard and an unchecked `git rev-parse` that made a gate fail-open) with **every
optional check enabled** — it flags neither. It is not on this list. A 15-line bespoke check found
both, because the invariant is repo-specific.

---

## 1. Mutation testing (PIT) on the Java modules — ✅ SHIPPED 2026-08-18

**Baseline + full findings: `docs/code-review/MUTATION-TESTING-BASELINE-2026-08-18.md`.**

Wired report-only over six modules (both library authorization surfaces, `spring-data`,
`keycloak-directory`, the starter, and `example-catalog-management-service`). `./gradlew mutationTest`,
~1m20s warm. **1668 mutations, 1123 killed, 98 survived**; test strength 83–94% per module.

Two corrections to what this item assumed — both measured, do not re-derive:

- **Not a Gradle plugin.** `info.solidsoft.pitest` last shipped 2023-09 and cannot apply on Gradle 9
  (`ReportingExtension.baseDir`, removed). PIT is wired as a `JavaExec` on its own CLI in the root
  build. `pitestRuntime` must stay **first** on the classpath — Boot's test starter drags ASM 9.7.1,
  which cannot read Java 25 class files.
- **PIT does not catch the defect that motivated this item.** Reverting `CatalogProvenanceAdviceTest`
  to its vacuous pre-fix form yields a byte-identical PIT report: a sibling test already covers the
  return value, and no mutator generates "return a rebuilt-but-equivalent object". **The manual
  discipline — prove a test by mutating the code it guards — stays a review step.**

**Follow-up (not scheduled):** close the five survivor rows in the baseline note — chiefly
`HttpOpaClient.isSafePath`, where making the policy-path validator accept everything fails no test —
then re-baseline. **Do not** gate on a repo-wide mutation score; see §What NOT to do next.

## 2. A guarded-substitution check for `scripts/` — ✅ SHIPPED 2026-08-18 ([#116](https://github.com/Void3110/spring-boot-starter-opa-abac/pull/116))

`scripts/checks/check-shell-guards.py` (+ `--fix`, + `test-shell-guards.sh`), CI job
`verification-layer`. **50 sites fixed across 13 runners** — not the handful this item implied.
Corrections worth keeping: classification must be over the WHOLE pipeline (`printf | python3` and
`printf | base64` both start with `printf` and both can fail); `local`/`declare`/`export` never trip
`set -e`; a substitution absorbing its own failure (`$(cmd || echo x)`) exits 0. The first draft
spliced `|| true` INSIDE a multi-line SQL string in `run-load.sh` — `bash -n` passed — which is why
the span scanner is quote-aware and the shape is a test arm.

**Value: high. Effort: ~20 lines.**

Two findings were the same bug: `X="$(cmd)"` under `set -euo pipefail` **aborts before** the
friendly `[ -n "$X" ] || { echo "..."; exit 1; }` guard below it, so the operator gets a bare exit
instead of the reason. `shellcheck` does not catch it (tested).

The check that does: parse each `run-*.sh`, find every assignment-from-substitution whose variable is
later guarded (`[ -n "$VAR" ]` or `require_token … "$VAR"`), and fail if that substitution lacks
`|| true`. This found `SHIPPED_MAX_AGE` and five token mints after a hand sweep had already "fixed"
three sites and declared victory.

## 3. Newman collection conformance lint — ✅ SHIPPED 2026-08-18 ([#116](https://github.com/Void3110/spring-boot-starter-opa-abac/pull/116))

`scripts/checks/check-collection-conformance.py` + `test-collection-conformance.sh`. **Clean at 18
collections**, so the arms that matter are the PRE-FIX shapes of the real E32b and E31j. The ~13
false positives below were two rules being too broad: the noun rule now matches only a CLAIM-shaped
noun (`<resource> ids?`), a positive `to.include` counts as an absence-control, and a LITERAL
`max_age="300"` pin is brittle but fails loudly rather than vacuously. No collection needs the
`owns-drill` waiver after that.

**Value: high — 5 of the first 16 findings were in one collection. Effort: medium (needs scoping).**

A prototype exists and was validated against the real pre-fix collection: it caught **both Mediums
and one Low** in milliseconds —

- **ABSENCE-ONLY** — a cell asserting only `not.include` over a collection with no count/length
  control passes trivially when the page is empty. (`E32b` guarded a leak against a page that
  teardown had already emptied.)
- **NAME-OVERCLAIM** — a cell whose title claims a resource its request never touches. (`E31j`
  promised product ids and only ever read categories.)
- **UNPINNED-WINDOW** — a cell that parses `max_age` without comparing it to the shipped value, so
  it passes on a rig left in the freshness drill.

**Before adopting:** it currently throws ~13 false positives across the other collections — the
step-up matrix legitimately reads drilled values because it *owns* the drill, and prose like
"MEMBERS UNAFFECTED" trips the noun rule. Needs per-collection scoping (a collection can declare
"I own the drill") and a tighter noun rule. **A noisy gate gets ignored.**

## 4. `verify-package.sh` should accept a declared-collaborative package — ✅ SHIPPED 2026-08-18 ([#116](https://github.com/Void3110/spring-boot-starter-opa-abac/pull/116))

`**Build: collaborative**` in the package index; arms [1]/[5] stand down. Near-misses are rejected
and collaborative-plus-a-prompt is a contradiction. `test-parts-gates.sh` 39 → 47.

**Value: medium. Effort: trivial.**

The gate fails two arms on any package built collaboratively rather than autonomously — it demands
`AUTONOMOUS-IMPLEMENTATION-PROMPT.md` and then reports "no prompt to check". SPA-CHALLENGE-UX is
legitimately prompt-less by an explicit roadmap decision, so its ship-time verify is red for reasons
that are not defects. Let the package index declare `**Build: collaborative**` and skip both arms.

## 5. Demo-console UI: replace the fixed-height shell with a sticky header — ✅ SHIPPED 2026-08-18 ([#117](https://github.com/Void3110/spring-boot-starter-opa-abac/pull/117))

Measured before/after at 1440×900: the window did not scroll at all, `main` grew an internal
scroller for **45px** of overflow, and that scrollbar sat **208px inset** from the window edge.
**The diff below is wrong in one respect** — there is no `--color-bg` token in this stylesheet, so
taken verbatim the sticky header would have been transparent. `--color-canvas` is the body's own
background and is what shipped.

**Value: medium (it is the first thing a visitor sees). Effort: two lines.**

`App.tsx`'s shell is `mx-auto flex h-full max-w-5xl flex-col` with `main` as `flex-1 overflow-auto`.
That scrolls **inside** a centred 1024px column, so the scrollbar sits in the middle of the window
rather than at its edge — and on a ~900px-tall viewport the header (73px) eats almost exactly the
overflow, producing a scrollbar for ~71px of content. Nothing is broken (no horizontal overflow at
any width tested, mobile included) but it reads as a glitch.

```diff
- <div className="mx-auto flex h-full max-w-5xl flex-col">
+ <div className="mx-auto flex min-h-full max-w-5xl flex-col">
-   <header className="flex items-center justify-between border-b …">
+   <header className="sticky top-0 z-10 bg-[var(--color-bg)] flex items-center justify-between border-b …">
-   <main className="flex-1 overflow-auto px-6 py-6">
+   <main className="flex-1 px-6 py-6">
```

Normal window-edge scrollbar, header still pinned, no phantom scroll. Pre-existing since the Phase
7.0 demo SPA (`46d63ff`); **verify in the Browser pane**, since no test in the repo covers layout.

## 6. Dedupe the per-catalog lookups in the demo console — ✅ SHIPPED 2026-08-18 ([#117](https://github.com/Void3110/spring-boot-starter-opa-abac/pull/117))

The measurement below was **2/3 right**. Real: `lookupTeamByTarget` ×2 (CatalogDetail + TeamPanel)
and `listTeamTagDefinitions` ×2 (CatalogDetail + **Roster** — a different second caller than the
stated cause). `listAllUsers` was ×1: that ×2 was `<StrictMode>`'s dev-only double-invoke, which
doubles EVERY count read off the dev-tools network tab. `App.catalog-fanout.test.tsx` mounts without
StrictMode and pins the production fan-out.

**Value: medium. Effort: small.**

Opening one catalog issues `teams` ×2, `users` ×2 and `tag-definitions` ×2, because `CatalogDetail`'s
`tagDefs` and the TeamPanel each resolve the governing team independently. Measured by wrapping
`window.fetch` and counting one grid→catalog navigation. **Not** the elevation chip's 1 Hz tick —
that was checked explicitly, since a tick-driven refire would have been a defect introduced by T4.

## 7. Guard `required_acr` against an acr the realm cannot mint — ✅ SHIPPED 2026-08-18 ([#118](https://github.com/Void3110/spring-boot-starter-opa-abac/pull/118))

`scripts/checks/check-step-up-acr.py`, in the `opa-policy-tests` CI job. **Not** startup validation:
the guide already explained why that would be wrong (the resource server would have to read realm
config it deliberately does not couple to), so this compares `step_up.json` against the realm
export's `acr.loa.map` instead. The guide already documented the footgun itself — the doc change is
an update, not the new section this item implied.

**Value: medium (operator footgun). Effort: small, or doc-only.**

Setting `step_up.required_acr` to a value the realm does not map produces a well-formed RFC 9470
challenge that **Keycloak then rejects outright** (`Invalid parameter: claims`). It does not
downgrade and does not authenticate, so the user lands on a Keycloak error page and the client never
sees a response it could explain. Either validate the setting against the realm's acr↔loa mapping at
startup, or at minimum warn where it is documented.

## 8. A jsdom component test for the SPA restoration effect — ✅ SHIPPED 2026-08-18 ([#117](https://github.com/Void3110/spring-boot-starter-opa-abac/pull/117))

`App.restore.test.tsx`, one `// @vitest-environment jsdom` file; suite 63 → 69 and still ~1.4s.
Mutation-proven on the defensive arm; two further mutations survive and are EQUIVALENT, not
uncovered (the initial view is already `{ kind: 'catalogs' }`) — recorded in the file.

**Value: medium. Effort: small.**

The restoration effect's defensive branch — a restored `getCategory` still refused after a completed
verification — has no test, because the vitest suite is deliberately pure-seam (no DOM) and the state
is the **structurally unreachable E12(b)** one. Its *happy* path is now proven live; the defensive arm
is verified by reading. A `// @vitest-environment jsdom` docblock on one new file closes it without
slowing the existing suite.

---

## 9. Three Boot-managed CVEs — waiting on Spring Boot 4.0.8

**Value: hygiene. Effort: one line, once upstream moves. BLOCKED on an external release.**

Added **2026-08-18** at the 1.2.0 pre-publish sweep (full detail and method:
`docs/code-review/PRE-PUBLISH-SWEEP-2026-08-18.md`). Three MODERATE advisories affect versions that
**Spring Boot 4.0.7 manages**, not versions this repo pins:

| Advisory | Dep | Fixed in |
|---|---|---|
| GHSA-5gvw-p9qm-jgwh, GHSA-mhm7-754m-9p8w, GHSA-5jmj-h7xm-6q6v | `com.fasterxml.jackson.core:jackson-databind:2.21.4` | 2.21.5 |
| GHSA-qv9r-c865-cp47 | `org.apache.logging.log4j:log4j-api:2.25.4` | 2.25.5 |

**Not fixed at the 1.2.0 cut, deliberately.** Boot **4.0.8 did not exist** on Central (checked), so the
only way to close them was to override the BOM's managed versions — which is the thing the BOM exists to
prevent — for advisories with **nil measured reachability** here: `@JsonView` **0** usages,
`@JsonUnwrapped` **0**, `MapMessage` **0**, and GHSA-5jmj needs case-insensitive deserialization, which
is never enabled. The one advisory on a version *this repo* pins was fixed in the release
(`jackson` 3.1.4 → 3.1.5).

**The action is to re-check, not to patch:** when a Boot 4.0.x patch lands, bump `springBoot` in
`gradle/libs.versions.toml` and re-run the sweep (§CVE of the sweep note has the exact OSV method —
query **resolved** coordinates via `dependencyInsight`, never the dependency tree). Override the BOM
only if Boot stalls *and* a reachable path appears.

---

## Related

- [[SPA-CHALLENGE-UX]] — the slice these came out of
- `docs/code-review/SPA-CHALLENGE-UX-REVIEW.md` — the three-round record, incl. the two structural
  mitigations: **prove a test by mutating the code it guards**, and **wire a gate to the write path
  it polices**
