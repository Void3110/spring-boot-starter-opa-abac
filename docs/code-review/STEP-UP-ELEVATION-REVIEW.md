---
tags:
  - status/active
  - type/review
  - area/abac
  - area/opa
  - area/spring
---

# Step-Up Elevation — Code Review

> **Verdict**: Approved with fixes
> **Scope**: Layer-3 whole-delivery review of the STEP-UP-ELEVATION slice (T1–T6, two orchestrated
> parts): the realm's conditional level-2 TOTP flow, the `elevated`/sole-blocker policy composition,
> the additive `decide()` envelope, the RFC 9470 challenge emitter + audit, the token miner, and the
> step-up e2e matrix. · **Branch**: `feature/void3110/step-up-elevation` vs `main` (57 files,
> +7062/−144 before fixes)

## Summary

Path 2B — the 8-lens adversarial workflow (fail-closed/authz, security-audit,
persistence/concurrency, core-boundary, rego-policy, API-contract, conflict/CI/dead-code,
infra/e2e; 29 agents), findings adversarially refuted, then the completeness critic. **16 findings
survived refutation (0 Critical, 6 Medium, 10 Low); 3 were refuted. All 16 fixed on the branch in
this commit.** The slice's load-bearing invariants all held under attack: no fail-open path in the
envelope, the sole-blocker emission is a rule-graph property, `filter`/`bulk` byte-identical,
core Spring-free, `allow()`/`allowAll()` unchanged. The two real security findings were both
*hardening* class, not exploits: a one-sided freshness window (a future-dated `auth_time` elevated
indefinitely — needs a skewed IdP clock, not an attacker primitive) and a missing startup guard for
the `act_chain` ingestion coupling (a config trim would silently widen agent calls to the human
branch; the e2e matrix would catch it, a downstream deployment would not).

## Critical Issues

None.

## Medium Issues

| # | Finding | Fix |
|---|---|---|
| 1 | **No startup wiring check binds `act_chain` to `opa.abac.subject.attribute-claims`** in the catalog service — a config trim silently disables the human-only supervised gate (the mcp-server's `ActorClaimWiringCheck` precedent was not applied here) | `StepUpClaimsWiringCheck` (`@ConditionalOnProperty` on the starter, like `SecurityConfig`'s ABAC beans): startup fails unless `act_chain` (widening guard) and `acr`/`auth_time` (silent feature-off guard) are ingested; + unit tests |
| 2 | **`catalog-api.yaml` declared no 401 anywhere** while four documented operations now emit it — the added `STEP_UP_REQUIRED` enum member was reachable from no declared response, and the `WWW-Authenticate` challenge was undiscoverable from the contract | `Unauthorized` response component (problem+json + the `WWW-Authenticate` header documented) `$ref`'d as `'401'` from `listCategories`/`getCategory`/`listProducts`/`getProduct` |
| 3 | **`REST-API-DESIGN.md` still stated the normative "No 401" rule** the branch just carved an exception into — two living guides contradicted each other | The rule is now "**`401` is step-up only**" with the carve-out explained + a 401 row in the error table |
| 4 | **The E2 audit assertion grepped the whole container log unscoped** — a rerun against the same pods passes on the previous run's lines (deleting the emission points would not be caught) | The grep is scoped to this run's freshly-created category id (both events carry it as `resourceId`) |
| 5 | **The miner-contract "E9" cell two committed docs cite did not exist** — issuer parity and the tampered-token control were verified once by a throwaway script and never pinned | An E9 preflight block in `run-step-up-matrix.sh`: `iss` parity with the in-network mints, a gateway 200 on the minted token, and a signature-corrupted 401 control; both doc references corrected to name the runner's preflight |
| 6 | **`AGENT-TOOL-AUTHORIZATION.md` still said the target gate never sees the delegation claim** — slice C made all three per-type policies key on `act_chain` and the list authorizer drop the supervised leg on it | The layer diagram (`→ + act_chain`) and the "honest cost" paragraph now record ADR 0030 Amendment 4's provenance-scoped narrowing input |

## Low Issues

| # | Finding | Fix |
|---|---|---|
| 7 | The `decide()` switch silently breaks consumers' Mockito stubs (default methods answer `null` on mocks) — the additivity note oversold "behaves identically" | `ABAC-AUTHORIZATION.md` decision-envelope section documents the mock caveat |
| 8 | `DenyReason`'s Jackson annotations were never exercised — a second, unverified declaration of the wire field names | Annotations dropped; the record documents that `HttpOpaClient`'s manual parser owns the wire names (pinned by its tests) |
| 9 | `deny_reason` hardcoded `required_acr: "aal2"` while `max_age` came from data — a level-2 rename in `data.step_up.loa` would advertise an ACR the map no longer accepts (the §7 re-auth loop) | `required_acr` added to `step_up.json` and read from data in both policies; `test_deny_reason_required_acr_comes_from_data` in both test files |
| 10 | **The freshness window had no lower bound** — an `auth_time` ahead of OPA's clock satisfied the one-sided `<=` by any margin and stayed elevated for the session's life, refresh after refresh | A lower-bound conjunct (`auth_time − now ≤ skew`) in both policies; within-skew-still-elevates + beyond-skew-denies cells in both test files (the deletion-mutation guard for the new conjunct) |
| 11 | The 401 challenge was minted for **any** well-formed `deny_reason.type` — an unknown type (typo, future class, tampered data) produced an unanswerable challenge | `challengeFor` requires `INSUFFICIENT_USER_AUTHENTICATION` before minting; unknown types fall back to the plain 403; test cell added |
| 12 | `docs/api/category-api.md` + `product-api.md` status lists and `errorCode` unions omitted the new 401 | 401/`STEP_UP_REQUIRED` rows, per-endpoint status lists, and the union member added |
| 13 | The runner read the authoritative window from OPA and discarded it while five collection cells hardcoded it | `shipped_max_age`/`drill_max_age` passed as env-vars; the five step-up cells read them; `infra/README.md`'s change-one-change-the-other note extended to the production-tier collection's seven literal cells |
| 14 | Findings 9/10 were byte-identical in the unswept mirror `product.rego` | Both fixed in the same edits (the mirror discipline) |
| 15 | `docs/api/README.md`'s catalog `errorCode` union + shared status table omitted `STEP_UP_REQUIRED`/401 | Both updated |
| 16 | The two sibling runners gained the miner dependency without the step-up runner's preflight — a missing miner burned ~93 s and died blaming a stale realm | The `python3` + `[ -x "$MINER" ]` preflight copied into both |

## Round 2 (verification round)

A second full 8-lens pass over the fixed tree (27 agents). It independently re-derived round 1's
findings against the committed HEAD — including reproducing the future-`auth_time` fail-open with
`opa eval` from scratch — which cross-validates the fixes, and surfaced **two genuinely new
findings**, both pre-existing in parts 0/1's code (not introduced by round 1's fixes), both fixed:

| # | Finding | Fix |
|---|---|---|
| 17 (Low) | `ResilientOpaClient.decide()` **retried reason-carrying denies**: the retryable-result predicate treated every deny as the fail-closed sentinel, but a deny carrying a reason is provably a real 200 answer (the delegate only builds one there) — so the step-up hot path (every unelevated supervisor's challenge) paid a deterministic-identical extra OPA hop plus an on-thread backoff, the exact inconsistency `allowAll()`'s MIXED discipline already refuses | The predicate excludes reason-carrying denies (`d == null \|\| (!d.allow() && d.denyReason() == null)`); a `RetryingGuard` test pins "asked exactly once" |
| 18 (Low) | The **absent/empty `data.step_up` off-state was pinned by zero tests** — both policies' comments claim "absent data ⇒ undefined ⇒ plain deny" (the exact state the drill's whole-document-PUT footgun produces) but every elevation test ran against the real `step_up.json` | `test_absent_step_up_data_closes_elevation_and_mutes_the_challenge` in both test files: `with data.step_up as {}` ⇒ no allow for fresh aal2 AND no `deny_reason` |

## Round 3

A third 8-lens pass over the **committed** tree (18 agents; rounds 1–2's fixes explicitly
excluded from re-reporting). It surfaced **one new Medium-class defect (mirrored, so two
findings) and five Lows**, all pre-existing, all fixed:

| # | Finding | Fix |
|---|---|---|
| 19 (Medium, both policies) | **The LoA lookup failed OPEN on a string-valued `loa` map**: Rego comparisons are a total order *across* types — `"1" >= 2` is `true` — so a data-value typo (a natural transcription from the realm's `acr.loa.map`, itself a JSON string) would elevate password-only `aal1` logins into the production tier, and every aal2 happy-path test would stay green while the aal1-must-not-elevate deny silently inverted. Reproduced with `opa eval` by the finder *and* independently by its refuter. Latent (shipped data is numeric), but this is the reference policy adopters copy | The level is bound and type-guarded (`is_number`) in both policies, and the threshold is now **`loa[required_acr]`, never a literal `2`** — which also closes finding 21 |
| 20 (dup of 19) | The critic's sweep: the identical defect alive in `product.rego` | Same edit, both files, same commit |
| 21 (Low) | The `>= 2` literal was decoupled from the advertised `required_acr` — raising `required_acr` to a higher level would not raise enforcement, and an unmapped name would challenge for an ACR that elevates nothing (the §7 loop) | Subsumed by 19's data-tied threshold; additionally `deny_reason` gained a coherence conjunct — the challenge is only minted when `loa[required_acr]` is a number, so incoherent data mutes the challenge (plain deny). Cells: string-map, mixed-map (the cross-type trap), unmapped-`required_acr` — both files |
| 22 (Low) | `docs/api/README.md`'s catalog vocabulary omitted `TAG_OPERATOR_MANAGED` (in the spec enum) and the 409 row said "user-mgmt only" | Both corrected |
| 23 (Low) | `infra/README.md`'s step-up runbook ordered `up` before `build` — pods stay on the pre-C image with nothing to say so | Order swapped (down → build → up) with the reason stated |
| 24 (Low) | `category-api.md`'s `ProblemDetail` union omitted `STEP_UP_REQUIRED` — the unswept twin of round 1's `product-api.md` union fix | Added |
| 25 (Low) | `scripts/postman/README.md`'s step-up row had the same up-before-rebuild sequencing | Reworded to the explicit order |

## Round 4

A fourth 8-lens pass (22 agents) returned **no code or policy defects** — every finding was a
documentation/comment tail of the earlier fix classes, plus one runner-plumbing latency. All fixed:

- **The up-before-build recipe survived in the three runner *headers*** (and the step-up runner's
  MCP-preflight error message) after round 3 fixed the READMEs — the unswept comment siblings of a
  fixed class. All reordered to down → build → up.
- **`collection_base_url` shadowing**: the step-up collection's REST cells resolve their base URL
  from the local environment file, so a non-default `GATEWAY` split the run (seeding/preflight on
  one port, cells on another). The refuter sharpened the fix: collection-level variables are
  *also* shadowed by the env file, so the override is runner-side — all three C-affected runners
  now pass `--env-var "collection_base_url=$GATEWAY/api/v1"` (CLI wins). The same latent class
  exists suite-wide in six pre-existing runners — spun off as its own follow-up task rather than
  rewriting ten untouched runners here.
- Stale B-era prose beside the C-flips: the production-tier runner header's E2/E4 lines, the
  collection's E2pre anti-vacuity comment, and the postman README's production-tier row all still
  described plain 403s — updated to the 401 + challenge shape.
- The `ProblemDetail` union sweep completed to all three per-page docs: `TAG_OPERATOR_MANAGED`
  added to category/product/catalog pages, `STEP_UP_REQUIRED` to catalog-api.md (the unions
  mirror the spec's shared enum, which declares both).

## Fail-closed verification

Every error/empty path lands on deny/empty — re-traced under the adversarial pass and after the
fixes: missing/unmapped/non-numeric claims ⇒ `elevated` undefined ⇒ the deny holds (U1–U3); a
**future-dated** `auth_time` beyond skew now also denies (the new lower bound — the one confirmed
one-sided edge, fixed); malformed wire reason ⇒ dropped at the parse (U14); outage/breaker ⇒
`OpaDecision(false, null)`, never a fabricated reason (U15, the `ResilientOpaClient` override);
partial reason ⇒ plain 403 (U18); **unknown reason type ⇒ plain 403** (new); unquotable parameters
⇒ plain 403, never a spliced header; audit failure changes no decision. An absent
`data.step_up`/missing key leaves `deny_reason` undefined ⇒ plain deny.

## Security audit

No widening found in the shipped configuration. The two hardening fixes: the unbounded-below
freshness window (10) and the unknown-type challenge downgrade (11). The challenge's
information-disclosure posture held: the sole-blocker rule means only a subject exactly one
elevation from allow ever sees a challenge; out-of-scope supervisors, writes, and agents get the
plain 403. Header injection re-verified: policy-sourced parameters pass a character allowlist with
a 403 fallback. The `act_chain` ingestion coupling is now startup-guarded (1). Three refutations
held (the null-`act_chain` claims are stripped by the extractor **before** the subject map is
built — measured by the refuters — so the presence-test never sees a key the policy would miss).

## Concurrency & idempotency

The slice adds no mutation path — the challenge path is read-only, the audit events are
fire-and-forget on a dedicated logger (emission failure swallowed by design, U19), and the
decision memo idiom is B's unchanged. The lens returned no findings; nothing to fix.

## Wiring & sibling sweep

Every new seam has a caller and a non-happy-path test: `decide()` (gate + both overrides),
`StepUpRequiredDecision` (advice + audit), the enum constant (advice + IT + e2e), the leg guard
(I3 + E6d), the miner's flags (the runner + E3/E7), the drill (E7a/E7b). Sweeps run for the fixes:
`product.rego` mirrored (14); the user-management service ingests **no** attribute-claims (no
wiring check needed there — sweep clean); the mcp-server has its own precedent guard; no leftover
window literals in the step-up collection; the production-tier collection's seven literal cells
are deliberate and now cross-referenced in `infra/README.md`.

## Autonomous-run check

- **Laziness**: one instance found — the E9 contract was verified by an uncommitted throwaway and
  both docs claimed a permanent cell that did not exist (5). Fixed by pinning it in the runner.
- **Self-preferential bias**: none found — STATUS notes' claims matched the diff everywhere else;
  STATUS-05 itself disclosed the E9 scratchpad honestly (the docs, not the STATUS, oversold it).
- **Goal drift**: none — the additive envelope, the byte-identical `filter`/`bulk` tails, the
  Spring-free core, and the single-policy-ticket boundary all verified mechanically.
- The part-1 **escalation** (T1's seeded factor breaking `sup-anna`'s ROPC in two shipped runners)
  was resolved by the maintainer before this review: harness-side `otp=` fix accepted, realm
  unchanged, I5(d) re-measured on the factored persona (STATUS-01 amendment).

## What's done right

The sole-blocker factoring (`denied` ≡ `stepup_denied ∨ denied_other`) makes challenge-leak
completeness a rule-graph property instead of a hand-kept list; eleven deletion-mutation guards pin
every new clause per file; the presence-test discriminator survives every falsy value shape; the
`ResilientOpaClient.decide()` override lands the default-method trap with an identity test; the
drill's leaf-path override + positive control + EXIT-trap restore is exactly the discipline the
window arithmetic needs; and the E6 seam deviation (tool-gate closes before the target gate — cells
moved to REST with the MCP fact kept as defence-in-depth evidence) was surfaced as a planning gap
rather than absorbed.

## Test results

- `./gradlew build` (all modules + example apps + Testcontainers ITs): **green** (after the fixes,
  including the new wiring-check tests and the unknown-type challenge cell)
- `./.sonar-local/sonar-local.sh`: **CLEAN — 0 open findings** on changed files (3× S5778 in the
  new test fixed, not suppressed)
- `opa test infra/opa/policies/`: **379/379** (367 from the slice + 6 round-1 mirror cells + 2
  round-2 off-state cells + 4 round-3 type/coherence cells); `opa check --strict` clean
- newman, re-run against the live rig after the fixes: `run-production-tier-matrix.sh` (the seven
  C-flip literal cells against the now data-sourced challenge) **green — 73/73**;
  `run-supervised-scope-matrix.sh` (miner preflight; both passes) **green — 48/48**;
  `run-step-up-matrix.sh` (E1–E7 + the new E9 preflight + the run-scoped audit grep)
  **green — 55/55**, with the E9 lines confirming iss parity, the gateway 200, and the tamper 401,
  and both audit events matched against this run's fresh category id
- One fix-of-a-fix caught by the re-validation itself: the E9 tamper control originally flipped the
  token's **last** character — the final base64url sextet of an RS256 signature carries padding
  bits, so the flip can decode to the *identical* bytes and the control fails against a healthy
  gateway, flakily. It now corrupts the **first** character of the signature segment
  (deterministically significant bits).
- Cross-runner ordering fact (pre-existing, recorded not fixed): `run-supervised-scope-matrix.sh`'s
  E8 pass recreates the catalog pods without the MCP flavour on its way out, so a subsequent
  `run-step-up-matrix.sh` fails its own MCP preflight until `ENABLE_MCP=1 ./deploy.sh up --pods 2`
  is re-run — the preflight names exactly that command, which is the designed behavior (a runner
  asserts rig state; it does not rebuild the rig).

## Commits

- The single review-fix commit carrying all 18 fixes and this note:
  `fix(step-up-elevation): layer-3 review fixes — the wiring guard, the bounded window, the
  data-sourced ACR, the 401 contract, the pinned E9, the retry discipline`
