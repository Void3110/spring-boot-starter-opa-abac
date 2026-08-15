---
tags:
  - status/active
  - type/review
  - area/abac
  - area/opa
  - area/spring
---

# Step-Up Elevation — Code Review

> **Verdict**: Approved with fixes — **thirteen adversarial rounds**, each round's fixes committed
> before the next ran, so every round reviewed the previous one's work
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

## Round 5

A fifth 8-lens pass (14 agents) — four Lows, zero refuted, all fixed:

- **The coherence guard completed to all three data axes** (the natural tail of rounds 2–3's
  class): `deny_reason` guarded `loa[required_acr]` but not `max_age`/`skew`, so a string-valued
  `max_age` or absent `skew` left `elevated` permanently undefined while the challenge was still
  emitted advertising an unsatisfiable window — the §7 loop by data malformation. Verified with
  `opa eval` by the finder and independently by the refuter. Both policies gained
  `is_number(data.step_up.max_age)` + `is_number(data.step_up.skew)` conjuncts with mirror test
  cells (`opa test` 381/381).
- **The compose issuer comment was measured-wrong by this branch's own probe**: `KC_HOSTNAME_URL`
  is an inert removed option on Keycloak 26 (`iss` follows the request's Host header via
  `KC_HOSTNAME_STRICT=false`, which the demo SPA's host-browser login needs), and APISIX validates
  the signature only, never `iss` (mx-e0ec23; E9's tamper control proves it). The comment now
  states the measured posture; the same truth was swept into the three mirrored doc sites
  (E2E-TESTING, postman README, infra README) that still claimed "the gateway rejects mismatched
  issuers". Gateway-side `iss` enforcement is spun off as a hardening follow-up task — a behavior
  change to the rig's auth surface does not belong at review close-out.

## Gateway issuer hardening (maintainer-directed, post-round-5)

Round 5's issuer finding was written up as a follow-up task; the maintainer pulled it into this
branch instead. It could not branch off `main` as the task described — the E9 preflight, the miner,
and the issuer notes it builds on exist only here — so it landed as its own commit on the slice
branch.

**The gap**: the openid-connect plugin validates the token signature against the realm JWKS but
never `iss`, and Keycloak 26's `iss` follows the request's Host header. `KC_HOSTNAME_STRICT=false`
is load-bearing (the demo SPA logs in through the host port from a browser), so hostname-v2 pinning
was rejected in favour of gateway-side enforcement — the fork the task itself anticipated.

**The fix**: an issuer-allowlist `serverless-pre-function` beside the openid-connect plugin on all
three OIDC routes (catalog, usermgmt, MCP). A well-formed bearer whose `iss` is neither rig
authority (`keycloak:8888` in-network, `localhost:28888` host-browser) is refused 401;
missing/malformed bearers pass through to openid-connect, which rejects them on its own terms — the
guard only narrows, and signature validation stays primary. E9 gained control **(f)**: a forged-Host
mint (`curl -H 'Host: evil.example'`) yields a realm-*signed* token with
`iss=http://evil.example:28888/…`, and the gateway must 401 it. Verified live: the control passes,
step-up 55/55, `run-tests.sh` 22/22 (canonical-issuer traffic unaffected).

## Round 6 — and why the loop rule earned its keep

The round intended as terminal instead caught a **Critical regression introduced by the hardening
commit itself**, reproduced live by its own refuter:

> **The issuer allowlist 401'd every demo-SPA token.** The SPA does not log in at
> `localhost:28888`: `auth.ts` sets its authority to `window.location.origin`, its whole PKCE flow
> runs through the gateway's `/realms/*` passthrough, and Keycloak rewrites its advertised issuer to
> the **gateway origin** (`localhost:9085`) — packaged *and* vite-dev (the `:3000` proxy sets
> `changeOrigin`). That origin was missing from the allowlist, so every SPA call to `/api/**`,
> `/api/v1/teams*`, `/api/v1/users*` and `/mcp*` would have been refused at the edge.

**Why the hardening's own validation missed it**: every runner in the suite mints in-network, so
55/55 + 22/22 were green while the browser path was broken. That is the shape of the defect —
*a token path no e2e exercises*. Fixed by allowlisting the gateway origin (the list is now a
documented, overridable `ISSUER_ALLOWLIST` naming all three real login paths), and by adding **E9
control (g)**: mint with the gateway's Host, assert **200**. The allow half of the cut is now pinned
beside the deny half, so the same omission fails loudly next time. The guard's JWT match was also
tightened to exactly three segments while there.

The other seven, all fixed:

| # | Finding | Fix |
|---|---|---|
| Medium | **`deny_reason`'s `granted` conjunct had zero real coverage** — the write-verb cell passed an already-elevated subject, so `stepup_denied` was already false and the case duplicated another; deleting `granted` left the suite green in both files | The cell now uses an unelevated subject, plus a new out-of-scope-supervisor cell (the conjunct's second bullet). Mutation-proven: deleting `granted` now fails **4** cells, previously 0 |
| Medium | The issuer control never exercised the browser authority (the gap above, as a test finding) | E9 control (g) |
| Low | `OpaClient.decide` is source-incompatible with an implementation already declaring `decide(AbacContext)` | Name-collision caveat documented beside the mock caveat |
| Low | The spec declared no 409 although the service emits `TAG_OPERATOR_MANAGED`/`STATE_CONFLICT` | `Conflict` response component, referenced from all six tag-carrying writes — and the product writes, which declared neither 409 nor 422 despite being taggable (ADR 0025), gained both |
| Low ×2 | Dead `REPO_ROOT` and an unread `open_product_id` env-var in the step-up runner | Removed |
| Low | `STEP_UP_REQUIRED` was the only enum constant missing from the per-constant status contract test | Added |

Five findings were refuted, including two attempts at the `act_chain: null` shape (the extractor
strips null claims before the subject map is built, so the presence-test never sees a key it would
miss) and one arguing `hasCompleteReason()` is dead (the enforcement path re-derives completeness
deliberately).

## Round 7 — the hardening's second self-inflicted finding

19 confirmed, 1 refuted. The headline is again a defect in the round-6 guard, live-reproduced by
the lens *and* independently by its refuter:

> **`Authorization: BEARER <token>` bypassed the issuer guard.** The guard matched `^[Bb]earer`,
> but the openid-connect plugin it rides beside lowercases the scheme before validating
> (`string.lower(res[1]) == "bearer"`, read from the running image). So the guard's *skip* set was
> larger than the authenticator's *reject* set: a foreign-issuer token under `BEARER` skipped the
> issuer check **and** authenticated — measured reaching the upstream on all four guarded routes.
> The guard's own comment ("it only narrows — a malformed bearer passes through to openid-connect,
> which rejects it") was therefore false for exactly this input.

Fixed by lowercasing the scheme before slicing, with the reason written into the guard's comment so
the next editor cannot re-introduce it. E9's foreign-issuer control now probes **three cased
spellings** (`Bearer`/`BEARER`/`bEaReR`) across **all three guarded routes** — it previously probed
one casing on one route, i.e. a guard installed on three routes was two-thirds unproven.

The other eighteen, all fixed except one deferred by design:

| Finding | Fix |
|---|---|
| **Spec**: `createProduct`/`updateProduct` can emit 503 (the fail-closed dictionary path) but declared none; `createCatalog` declared a 409 it cannot emit (my own over-broad round-6 addition — a fresh catalog has no governing team, so no tag write happens) | 503 added to both product writes; the 409 dropped from `createCatalog` |
| **Five issuer notes still said "two rig authorities"** and attributed the SPA's login to `localhost:28888` — the *exact belief that caused the round-6 regression*, still committed in prose | All five swept to three authorities with the gateway origin named and explained; the inert `KC_HOSTNAME_URL` deleted (its own comment already called it dead) |
| **Rego**: the coherence guard checked type but not *range* — a negative `max_age`/`skew` is well-typed but unsatisfiable, so a challenge was still minted | `>= 0` conjuncts on both axes + mirrored cells (`opa test` 385/385) |
| **`act_chain: null` was documented as an agent call in all three policies but cannot be** — the extractor strips null-valued claims before the subject map exists (the same claim was *refuted twice* in earlier rounds as "not a fail-open", which is true and orthogonal: the docs were still wrong) | The shape list now says which shapes the claim can *arrive* as, and names the extractor's contract |
| **`docs/api` 409 rows + per-endpoint status lists**; two javadocs stale (`ResilientOpaClient` "three decision calls" after `decide` made four; `ApiExceptionHandler` claiming the inherited mapping is always 403) | All updated |
| **The persona registry still called production-tier "the one sanctioned exception"** for sharing `sup-anna`'s reporting edge — step-up is a second sharer | Amended to two, with step-up's compliance with both safety rules stated |
| **The `collection_base_url` override was still missing from four sibling runners** | Swept — the follow-up task's scope collapsed to the newman-export flag alone |

**Escalated, then decided by the maintainer (Medium, CORE_BOUNDARY):** the published library
hardcoded the *example's* vocabulary — the literals `"supervised"` and `"production"` — to decide
when to emit `SUPERVISED_PRODUCTION_READ`, so no adopter could configure or suppress it. Rather than
reverse a settled ADR mid-review, the three options were written up for the maintainer, who chose
the **configurable seam**:

> **ADR 0030 Amendment 7.** What §8 pinned is *computability* — the trigger evaluates inside the
> decision, from the allow, the resolved role and the enriched root attributes at that instant, so
> elevation is implied by the allow and never re-derived app-side. That pin is why the trigger stays
> in the manager and is **unchanged**; only the vocabulary became configuration. A
> `PrivilegedReadAuditPolicy` (provenance · root attribute · root values, matched scalar-or-array
> like `root_env_values`) is built by the starter from `opa.abac.audit.privileged-read.*`, and
> **unset means silent** — the three-argument constructor is retained and now means exactly that,
> the right default for every pre-C adopter. The example service opts in with its own nouns.
> Moving the trigger app-side (option c) was rejected on the ADR's own terms: it forces the
> re-derivation Amendment 3 forbids.

Two cells pin the seam (unconfigured is silent on the very input that otherwise audits; a different
vocabulary — `oversight`/`classification`/`restricted` — fires correctly), and the rig proof is
unchanged in substance: E2's audit grep still finds **both** events on the live pods.
`STEP_UP_CHALLENGED` is vocabulary-free and untouched.

## Round 8 — the mutation-coverage round

Seven Lows, two refuted, **no defects in shipped behavior**: every finding was a *guard that works
but nothing pins*. The lenses proved each one by deleting the conjunct and observing the suite stay
green — the same discipline the slice used for its own eleven deletion-mutation guards, now turned
on the guards the review itself added.

| Unpinned conjunct | Why the existing cells missed it | Now pinned by |
|---|---|---|
| `elevated`'s `is_number(required_level)` | The string-map case trips the *level*-side guard first, so the threshold-side guard was never the failing conjunct. Deleting it lets `1 >= null` hold — Rego orders `null` below every number — so a password-only `aal1` subject clears an `aal2` threshold | a **null-valued** `loa[required_acr]` beside a well-typed level |
| `deny_reason`'s `is_number(skew)` | The only skew variation was an *absent* key, which both skew guards mute redundantly | a **present but string-valued** `skew` |
| `deny_reason`'s `skew >= 0` | same | a **negative** `skew` inverting the window |

Each now fails its mutation (2 cells per conjunct — the mirrored pair), verified by deleting all
three in turn against the restored tree. `opa test` 385 → **387/387**.

The other four:

- **The catalog root's supervised-agent deny had no rig cell** — `catalog.rego` gained the agent
  deny in T2, but E6 only exercised the child types. Added **E6i**, mirroring E1pre's human 200 so
  the root type gets the same allow-vs-deny contrast.
- **QA case I5's keystone — "a refresh grant preserves `auth_time`" — had no committed test
  anywhere.** It is the claim the entire freshness design rests on (it is *why* a short token
  lifetime would prove nothing), and it had been measured once by a scratchpad script that was
  never committed. Added as **E9h**: mint the elevated token's refresh token, exchange it, assert
  `auth_time` unchanged **and** `iat` advanced (the second half stops the first from passing
  vacuously on an unrefreshed token).
- **The three DELETE operations can emit 409** (`STATE_CONFLICT`) but declared only 204/403/404 —
  and `catalog-api.md`'s table still listed `STATE_CONFLICT` under a literal `—` status. Both
  fixed.

## Rounds 9–10 — the vocabulary sweep, completed

Round 9 (six Lows) added a **library-side** range guard on the advertised window: round 5 had
guarded `max_age`/`skew` in the *example policy*, but this library is published for adopters who
write their own, so the emitter now refuses a non-positive window on its own terms. It also fixed
the branch's own runner reproducing the newman JSON-export defect the note documents (the correct
pattern already existed in-repo), the `OpaClient` javadoc's stale count, the PUTs' missing 400, and
a second inert Keycloak-v1 option.

Round 10 then caught what round 9's *own* audit fix had left half-done — three siblings of
Amendment 7, all in the published library:

| Sibling | Why it mattered more than the trigger |
|---|---|
| The event **name** `SUPERVISED_PRODUCTION_READ` | It is the string that lands in an adopter's logs, and it was the last piece of this repo's example vocabulary baked into the library. → **`PRIVILEGED_READ`**, matching the property and the policy class |
| The **challenge description** | *Worse than the audit event*: it reaches the **caller**. Every RFC 9470 `error_description` and problem `detail` asserted "…to read production content" — a false, domain-inappropriate fact for an adopter whose step-up guards a payment confirmation. → a domain-neutral default plus an overridable `stepUpChallengeDescription()` seam; the example overrides it |
| A **half-configured** policy block | It silently disabled the event. Silently disabling an audit control on a typo is how oversight quietly stops happening. → entirely-absent still means off; partial fails startup naming every knob |

Two smaller ones: the four challenge-suppression branches were completely silent and now warn; and
the allowlist admits a comma — *my own* neutral default sentence was suppressed by an allowlist that
forbade it, which is precisely the silent 403-downgrade an adopter writing an ordinary sentence
would have hit. The spec also stopped claiming "no authentication" while documenting an
authentication challenge (it now declares `bearerAuth`).

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
- `opa test infra/opa/policies/`: **389/389** (367 from the slice, plus cells added by rounds 1, 2,
  3, 5, 8, 11 and 13 — mirror, off-state, type/coherence, window-axis, mutation-pinning, live-window
  and skew-axis); `opa check --strict` clean and `opa fmt` clean.

  **Mutation status, stated precisely** (round 15 measured it, round 18 corrected this claim): every
  step-up conjunct that DECIDES fails when deleted — including both window axes independently
  (`skew >= 0` and `max_age + skew > 0`, each killing 2 cells) and the decisive
  `is_number(loa[required_acr])`. Two conjuncts deliberately do NOT fail alone:
  `is_number(max_age)` and `is_number(skew)` are subsumed by the arithmetic in `max_age + skew`
  (arithmetic on a non-number is a type error ⇒ rule undefined ⇒ challenge already muted). They are
  retained as belt-and-braces because that subsumption rests on a subtle asymmetry — `+` errors
  where `>=` silently orders across types — and both the policy and test comments now say so rather
  than claiming they are load-bearing.
- newman, re-run against the live rig after each round: `run-production-tier-matrix.sh` (the seven
  C-flip literal cells against the now data-sourced challenge) **green — 73/73**;
  `run-supervised-scope-matrix.sh` (miner preflight; both passes) **green — 48/48**;
  `run-filter-matrix.sh` (the base-url override) **green — 29/29**; `run-tests.sh`
  **green — 22/22**; `run-step-up-matrix.sh` **green — 58/58** (E1–E7 + E6i's root-type agent deny
  + the E9 preflight's nine controls: iss parity, gateway 200, tamper 401, foreign-issuer 401 across
  three cased spellings and three guarded routes, gateway-origin 200, and refresh-preserves-
  `auth_time`), with **both** audit events grepped off the pods against this run's fresh category id
  — through the configurable audit seam, which is what proves Amendment 7 preserved behavior
- **A `run-tests.sh` failure chased to its root and found NOT to be this branch's**: the realm export
  pins no user ids, so every `deploy.sh down`+`up` re-imports the realm and mints `demo` a fresh
  `sub`, orphaning its `app_user` row (the DB volume survives `down`); `run-tests.sh` is the only
  runner that never bootstraps its own user, so its claim step 400s with "No acting user: request is
  unauthenticated" — which reads as an auth regression and is a missing profile row. Proven by
  probing the pod directly (identical failure ⇒ not the gateway guard), by the duplicate
  same-`display_name` rows (one per re-import), and by bootstrapping the current `sub` → **22/22**.
  Spun off as a follow-up; recorded in Mulch
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

One commit per review round, plus the maintainer-directed hardening:

- `ec3c1fd` — rounds 1–2 (18 fixes): the wiring guard, the bounded window, the data-sourced ACR,
  the 401 contract, the pinned E9, the retry discipline
- `e3db8d4` — round 3: the type-guarded, data-tied elevation threshold
- `bcea0a6` — round 4: the runner-header recipes, the base-url override, the C-flip prose sweep
- `139284e` — round 5: the full-axis coherence guard, the measured issuer posture
- `ea0c0d4` — `harden(gateway)`: the issuer-allowlist pre-function + E9's foreign-issuer control
- `ce75e4c` — round 6: the SPA-breaking allowlist regression + the vacuous `granted` cells
- `4594c61` — round 7: the guard's case-variant bypass, the spec's 503/409, the issuer-note sweep
- `a7a4ddf` — `refactor(audit)`: ADR 0030 Amendment 7 (the vocabulary seam)
- `6f3e6bf` — round 9: the library-side window guard, the newman reporter, three doc tails
- `b41defc` — round 10: Amendment 7's sweep completed to the wire-visible half
- `c626f82` — round 11: the window guard tested the wrong quantity
- `4ac0095` — round 12: the compatibility shim, the third audit knob, three stale texts
- round 13: the skew axis restored (see below)

## Round 13 — the regression a fix introduced, caught by the next round

Round 11 correctly found that `max_age >= 0` was a false invariant, but its replacement collapsed a
**two-axis** guard onto one: `elevated` has *two* freshness conjuncts, and the second is keyed on
`skew` alone. With `skew: -10, max_age: 300` the sum stays positive, so a challenge is minted — yet a
**fresh** re-authentication fails `0 <= -10` and is challenged again for |skew| seconds. That is ADR
0030 §7's loop, reintroduced in the very rule written to prevent it, and it slipped through because
round 8's `skew >= 0` mutation guard had been passing only by virtue of the *sum* test since round 11.
Both axes are restored (`skew >= 0` **and** `max_age + skew > 0` — exactly the predicate for "a fresh
re-auth clears the deny") and **each now fails independently under mutation**. Also: the list
operations declare their reachable 404, a truncated comment from round 10's sweep is repaired, and the
rename reached the planning package.

## Spun-off follow-ups (deliberately out of this branch)

One pre-existing suite-wide pattern the review surfaced, kept out rather than ballooning the
slice's diff:

- **The newman JSON export never writes** in most runners: they pass `--reporter-json-export`
  without activating the json reporter, so their report directories are empty and assertion counts
  must be read from console output. The correct pattern already exists in-repo
  (`run-resilience-matrix.sh` uses `--reporters cli,json`) and round 9 applied it to *this
  branch's own* runner; the pre-existing runners still need the one-flag sweep.

*(The `collection_base_url` shadowing that was listed here as a second follow-up was completed on
this branch in rounds 4 and 7 — every runner now passes the override.)*
