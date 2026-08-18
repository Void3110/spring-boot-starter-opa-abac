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

## 2. A guarded-substitution check for `scripts/`

**Value: high. Effort: ~20 lines.**

Two findings were the same bug: `X="$(cmd)"` under `set -euo pipefail` **aborts before** the
friendly `[ -n "$X" ] || { echo "..."; exit 1; }` guard below it, so the operator gets a bare exit
instead of the reason. `shellcheck` does not catch it (tested).

The check that does: parse each `run-*.sh`, find every assignment-from-substitution whose variable is
later guarded (`[ -n "$VAR" ]` or `require_token … "$VAR"`), and fail if that substitution lacks
`|| true`. This found `SHIPPED_MAX_AGE` and five token mints after a hand sweep had already "fixed"
three sites and declared victory.

## 3. Newman collection conformance lint

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

## 4. `verify-package.sh` should accept a declared-collaborative package

**Value: medium. Effort: trivial.**

The gate fails two arms on any package built collaboratively rather than autonomously — it demands
`AUTONOMOUS-IMPLEMENTATION-PROMPT.md` and then reports "no prompt to check". SPA-CHALLENGE-UX is
legitimately prompt-less by an explicit roadmap decision, so its ship-time verify is red for reasons
that are not defects. Let the package index declare `**Build: collaborative**` and skip both arms.

## 5. Demo-console UI: replace the fixed-height shell with a sticky header

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

## 6. Dedupe the per-catalog lookups in the demo console

**Value: medium. Effort: small.**

Opening one catalog issues `teams` ×2, `users` ×2 and `tag-definitions` ×2, because `CatalogDetail`'s
`tagDefs` and the TeamPanel each resolve the governing team independently. Measured by wrapping
`window.fetch` and counting one grid→catalog navigation. **Not** the elevation chip's 1 Hz tick —
that was checked explicitly, since a tick-driven refire would have been a defect introduced by T4.

## 7. Guard `required_acr` against an acr the realm cannot mint

**Value: medium (operator footgun). Effort: small, or doc-only.**

Setting `step_up.required_acr` to a value the realm does not map produces a well-formed RFC 9470
challenge that **Keycloak then rejects outright** (`Invalid parameter: claims`). It does not
downgrade and does not authenticate, so the user lands on a Keycloak error page and the client never
sees a response it could explain. Either validate the setting against the realm's acr↔loa mapping at
startup, or at minimum warn where it is documented.

## 8. A jsdom component test for the SPA restoration effect

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
