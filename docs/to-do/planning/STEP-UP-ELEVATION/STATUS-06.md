---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/spring
---

# STATUS — T6: e2e: the step-up matrix, the freshness drill, and non-regression

**Status:** ✅ DONE

## What shipped

**`scripts/postman/step-up-matrix.postman_collection.json` + `run-step-up-matrix.sh`** — the round
trip proven on the rig, on exact status codes, header parameters, body codes, ids and log lines.
Fixture prefix **`f00d…`** (registered): a **production** catalog and an **untagged** one, one
category + product each, under two `Step-Up *` teams owned by `editor`, who is also `sup-anna`'s
single report. Six folders, **55 assertions**, all green:

| Folder | Cells |
|---|---|
| `Matrix` (25) | **E1pre** anna reads the production *catalog itself* → 200 (the deny below is the tier, not a missing role); **E1a–c** her `aal1` child reads → **401** + the RFC 9470 challenge asserted **by value** (`error="insufficient_user_authentication"`, `acr_values="aal2"`, `max_age="300"`) + `application/problem+json` + `STEP_UP_REQUIRED`; **E1d** the untagged catalog stays **200** at `aal1`; **E2a–c** the same three reads with an `aal2` token → **200** on exact ids; **E4a** an out-of-unit supervisor → **plain 403**, no `WWW-Authenticate`, `ACCESS_DENIED`; **E4b** *elevated* anna's `PUT` → the same plain 403; **E5a/b** the owner at plain `aal1` → 200 with `_actions` present and honest |
| `E6 agents` (13) | **E6a–c** an agent-client token for anna's own subject → plain 403 on the production category, the production product **and** the untagged category (any tier); **E6d** its catalog list → **200 with zero rows** (the leg is skipped, not denied); **E6e/f** the same subject **without** the claim reads that row and sees both catalogs — the controls that make E6c/E6d falsifiable |
| `E6 agents through MCP` (8) | **E6g/h** through the tool surface, *both* the agent and anna herself are refused at the **tool-gate** — measured, and the reason the cells above are REST (see Decisions) |
| `E7a` (1) | the drill's **positive control**: a fresh elevation still opens production under the 5-second override — proof `loa` survived the leaf PUT |
| `E7b` (3) | after `> max_age + skew`, the **same bearer** answers 401 + the challenge again, now carrying `max_age="5"` — the parameter tracks the policy **data**, not a local copy |
| `E3` (5) | the loop-prevention negative **inside** that window: a genuinely new token (`iat` advanced) carrying the **same** `auth_time` → still 401 |

The runner owns four rig behaviours rather than assuming them: it **restarts OPA** and polls a
**real decision** (never `/health`); it asserts `data.step_up` actually loaded and prints the shipped
window; it runs a **dual-identity preflight** (acr on both of anna's tokens, `act_chain` present on
the agent token and absent from every human one, `auth_time` absent from both ROPC tokens) so no cell
can silently degrade into a different cell; and it **greps both audit events** off *every* catalog
pod, asserting `STEP_UP_CHALLENGED` carries **no** `authTime`. The drill's `EXIT` trap restores the
shipped data by restarting OPA and is installed **before** the override, so a run that dies mid-drill
still leaves the 300-second window behind it.

**The seven enumerated C-flips**, in `production-tier-matrix.postman_collection.json` and nowhere
else: **E2a, E2b, E2c, E2d, E4b, E4c, E5d** now assert **401 + the challenge parameters by value +
`STEP_UP_REQUIRED`** instead of the plain 403 slice B pinned. Each carries an in-cell `C-FLIP`
annotation naming the amendment and pointing at where the closed-tier proof **moved** (the step-up
matrix's E7 drill closes the very same read again). The rewrite is deliberately a **stronger**
assertion than the code it replaces. The set was applied programmatically with an equality assertion
on the cell list, so "exactly those seven" is mechanical rather than eyeballed.

**Doc deltas** — `scripts/postman/README.md` gains the runner/collection row, the miner's own file
row and the `f00d…` registry row (plus the one documented exception to the in-network caveat);
`docs/guides/TEAM-BASED-AUTHORIZATION.md` gains **only** the e2e paragraph appended to T4's *Step-up
elevation* subsection; `infra/README.md` gains *The step-up matrix* subsection (T1's realm note above
it untouched) plus the measured ROPC exception below.

## Tests

`opa test infra/opa/policies/` **367/367** and `./gradlew build` **green** — both unchanged by this
ticket, which touches no `.rego` and no `.java`; the local Sonar gate does not apply (zero changed
`.java` files).

**E8 — the non-regression enumeration.** Every one of the ten named runners was **run**, on the
`ENABLE_MCP=1` flavour (a superset of what each needs, so the whole set runs on one rig — the B
lesson). None was skipped.

| Runner | Result |
|---|---|
| `run-supervised-scope-matrix.sh` | ✅ 42 + 6 (the E8 second pass) — A's matrix is C-invariant and passed **unmodified**; anna's ordinary non-production reads are exactly what B shipped |
| `run-production-tier-matrix.sh` | ✅ 73 — with the seven enumerated C-flips; **every other cell** (E1, E2pre, E3, E5a–c, E5e–g, E6, E7, E4a/E4d/E4e) unmodified |
| `run-tests.sh` | ✅ 22 — green on the second attempt; the first tripped on a **transient**, see Decisions |
| `run-filter-matrix.sh` | ✅ 29 |
| `run-hierarchy-list-matrix.sh` | ✅ 9 + 9 (two passes) |
| `run-isolation-matrix.sh` | ✅ 20 |
| `run-action-enrichment-matrix.sh` | ✅ 19 |
| `run-agent-tool-matrix.sh` | ✅ 78 across its eight passes — the roster and deny cells still hold with the three new claims ingested |
| `run-resource-resolution-matrix.sh` | ✅ 12 |
| `run-tag-matrix.sh` | ✅ 16 |

**The shared-persona ordering rule, honoured rather than assumed** (Mulch mx-a10787): the three
runners that touch `sup-anna`'s reserved family were run **back to back in both orders** —
production-tier → supervised-scope → step-up, then supervised-scope → step-up → production-tier —
green both times. Each deletes the edges it manages before seeding, and the step-up runner binds **no**
reserved-family account into its own teams (both `Step-Up *` teams are owned by the shared `editor`
seeder).

## Architecture review + refactor

Path: **inline self-review** (the ★ gate).

- **Fail-closed, in an e2e's own terms.** A matrix cannot widen a decision; what it can do is pass
  while proving nothing, which is this slice's characteristic failure. Every cell was built with its
  own falsifier: **E1pre + E1d** guard the challenge cells (she *is* granted, and the tier is what
  bites); **E7a** guards **E7b** (a whole-document PUT would have clobbered `loa`/`skew` and produced
  an instant, vacuous 401 — the runner additionally asserts both survived); **E6e/E6f** guard
  **E6c/E6d** (a closed door and an empty reach look identical from outside); **E3**'s `auth_time`
  equality is asserted **shell-side before newman runs**, so a cold cookie jar aborts the run instead
  of quietly proving nothing; and the audit grep asserts an **absence** (`no authTime` on the
  challenge event) alongside the two presences.
- **The assertion-style convention, checked mechanically not by eye.** Every one of the 25 `pm.test`
  callbacks in the new collection is a `function () { … }` that throws; a scripted scan for the
  `() => pm.response.code === N` shape (which newman ignores, so the cell passes unconditionally)
  found **zero** occurrences in the new collection and none introduced into production-tier's.
- **The widenings named for this ticket, and why they cannot happen.** (a) *A C-flip rewritten too
  broadly, accepting a 401 where B pinned a 403 for a different reason* — the flip was applied by a
  script asserting the patched set equals the enumerated seven, and each rewritten cell asserts the
  challenge **parameters by value** plus the body code, which is strictly narrower than "not 403".
  (b) *The runner leaving the rig on a 5-second window for the next matrix* — the `EXIT` trap is
  installed before the override and restores by OPA restart; the full non-regression sweep ran
  **after** the drill and is itself the evidence. (c) *A runner asserting repository state rather
  than rig state* (the recorded design smell) — every preflight here probes the running rig: a real
  OPA decision, `data.step_up`'s contents, the claims on the minted tokens, the MCP container.
- **Wiring.** Every seam has a consumer and a non-happy path: the miner's four flags (a cell each),
  `--print-otp` (three runners), the leaf-path override (E7a is its guard), the audit channel (two
  presences + one absence), the C-flips (production-tier re-run green).
- **Boundary.** No `.rego`, no `.java`, no realm, no SPA, no `opa-abac-spring-data`, no
  `HierarchicalAuthorizer`, no `agent_tools.rego`, nothing in `filter`/`bulk`. The two pre-existing
  runners changed **only** in how they mint anna's token — no cell, no assertion, no fixture, no
  ordering altered. `supervised-scope-matrix.postman_collection.json` is byte-identical.
- **Pattern reuse.** The runner is production-tier's shape (self-reset, ltree-seeded catalogs,
  contents created through the gateway, assignment-then-read instead of the exit-swallowing
  herestring, teardown-on-green); the MCP cells reuse the agent matrix's streamable-HTTP helpers
  verbatim in idiom; the readiness poll is mx-9fef93's; the drill's trap discipline is mx-a5a1b6's;
  the flip follows mx-a10787's four rules (plan it, flip-never-delete, upgrade the assertion while
  you are there, sweep the prose that pinned the old contract — the README row and the runner header
  were both updated).
- **Refactors applied.** Three, all forced by measurement rather than taste, and all recorded as
  decisions below: the E6 folder was restructured from MCP-only to REST + MCP; the three runners that
  mint `sup-anna` learned to answer the direct-grant OTP step; and the miner learned to tolerate an
  empty cookie jar. Nothing was churned for its own sake.

## Integration / e2e

Everything above ran against the live rig on the `ENABLE_MCP=1` flavour after a `./deploy.sh down`
(the realm changed) with all three app images rebuilt to carry part 0's code. Final state: the
step-up matrix **55/55**, the ten non-regression runners green, `opa test` 367/367, `./gradlew build`
successful, and the rig restored to the shipped 300-second window.

## Decisions

- **Seam deviation — the MCP tool-gate closes the supervised path *before* the target gate, so E6's
  target-gate cells cannot live there.** The decomposition says the agent cells run "through the MCP
  surface". Measured: every tool call by `sup-anna` — the agent token **and** her own human token —
  is refused with `layer: tool-gate`. The cause is structural and correct: `agent_tools.rego`'s
  principal ceiling is `permissions.effective_actions(role_definition, target_type)`, which is
  **membership-derived**, and a pure supervisor is a member of nothing. Her reach comes from a
  reporting relation the tool-gate knows nothing about, so the intersection is empty whatever the
  capability says. An MCP 403 would therefore have proved the *tool-gate* and said nothing about C's
  agent deny. Resolution: the six target-gate cells run over **REST** with the same agent-client
  bearer the MCP proxy forwards verbatim (ADR 0028 propagates nothing, so this is the identical
  decision), and the MCP fact is kept as its own two-cell folder — it is genuine defence in depth
  (the supervised path is closed to agents at **both** layers, tool-gate first) and it documents why
  the others are REST. Recorded as a planning gap rather than absorbed silently.
- **ESCALATION (cross-part):** T1's seeded `otp` credential on `sup-anna` silently broke her **ROPC
  direct grant**, and this reaches **part 0** — T1's realm deliverable and its I5(d) evidence.
  Keycloak's direct-grant flow demands a code from any identity that owns a factor, so a plain ROPC
  mint for her now answers `invalid_grant / "Invalid user credentials"` — which reads as a wrong
  password and is not. It broke **two shipped runners** (`run-supervised-scope-matrix.sh`,
  `run-production-tier-matrix.sh`), i.e. slices A and B, not only this one. STATUS-01 enumerated the
  ROPC side effects and concluded "ROPC is untouched", but its I5(d) cell measured `editor` — a
  persona **without** a factor — so the one persona the change actually affects was never probed.
  T6 repaired the **harness** inside its own boundary (the three runners now pass an `otp=` parameter
  computed by `mint-code-flow-token.py --print-otp`, so the fixture secret and the RFC 6238
  parameters stay in one place, with a next-window retry for Keycloak's one-time-use rule), and every
  affected runner is green. What is **not** T6's to decide, and is why this is escalated: whether the
  realm should instead exempt the direct-grant flow for fixture factors, and whether I5(d) should be
  re-measured on a factored persona. No realm file was touched by this part.
- **`run-tests.sh` failed once on a transient, not a regression.** Its "claim the catalog"
  step answered `400` when run **immediately after** `run-supervised-scope-matrix.sh`, whose E8 pass
  recreates the catalog pods on the way out; the user-service's ownership lookup reaches a pod that
  is still starting. Reproduced by hand once the rig settled: `201`. Re-run green (22 assertions).
  Worth knowing rather than worth guarding: the supervised-scope runner waits for its own pods but
  the next runner has no reason to.
- **The step-up runner documents the down-first re-import and *detects* a stale realm; it does not
  perform the teardown itself.** The sibling precedent (`run-supervised-scope-matrix.sh`) is a header
  prereq plus a loud preflight, and the recorded rule is that a runner asserts **rig** state. A
  runner that tore the rig down and rebuilt it would also make the ten-runner sweep an hour longer.
  The preflight that catches the stale realm is anna's `aal2` mint: on a pre-C realm the level-2
  subflow does not exist, the miner cannot reach `acr: aal2`, and the runner prints the `down`-first
  instruction.
- **`ENABLE_MCP=1` for the whole set, not two flavours.** It force-enables OIDC + OPA + the
  user-service, so it is a strict superset of what every other cell and every non-regression runner
  needs. Running the sweep on one rig is the B lesson applied.
- **The drill waits 42 s, not 35.** `max_age + skew` is 35 under the override; the extra margin
  absorbs the second-granularity of `auth_time` and the request round trip, so the cell fails for
  the reason it is about rather than for a boundary race. The exact boundary arithmetic is
  `opa test`'s job with pinned clocks (U6), not the wire's.

## Part review (layer 2)

**Scope:** part 1 = **T5–T6** as one diff (`505fdc5..HEAD` on `feature/void3110/step-up-elevation`) —
the code-flow token miner, the step-up matrix and its runner, the seven C-flips, the anna-token fix
in two pre-existing runners, and four doc sections.

**Path — and the downgrade, recorded.** Applied **inline, in this context, as the 2A lens set**. The
deep-review skill's own path table routes an inside-a-subagent review to *2A inline, any size, any
risk*; the multi-lens 2B path is unreachable where a part-runner runs. **T6 is a headline ticket**
(E2's round-trip liveness, E3's loop prevention, E6's human-only proof), so it is covered here by the
inline pass rather than by the multi-agent fan-out it would otherwise get — **layer 3 re-covers it**
over the whole delivery.

**Lenses applied and what they found:**

1. **Test honesty — the lens that matters most for a part that ships only proofs.** Re-read every new
   cell asking "what would have to break for this to fail?". Four cells had no falsifier when first
   written and gained one: E1's challenge cells (added E1pre and E1d), E7b (added E7a's positive
   control **and** the runner's `loa`/`skew` survival assertion), E6c/E6d (added the E6e/E6f
   same-subject human controls), and E3 (moved the `auth_time` equality **before** newman so a cold
   cookie jar aborts). A scripted scan confirmed zero vacuous `pm.test` callbacks. **No finding left
   open.**
2. **Fail-closed spine, end to end on the wire.** Part 0 proved each failure class at its own layer;
   this part is where they compose. Observed live: not-elevated → 401 with a *complete* challenge;
   not-granted (out-of-unit) → plain 403 with **no** header; not-granted (write verb) → the same;
   agent → plain 403 at any tier; expired elevation → 401 again with the challenge's `max_age`
   tracking the **data** (`"5"` under the override, `"300"` shipped) — which is invariant 7 made
   observable rather than asserted. No path produced a challenge the policy did not emit. No finding.
3. **The seven invariants, re-checked against the part's own diff.** (1) sole-blocker — E4a/E4b/E6a–c
   are its four negative wire cells. (2) elevation-proof unproven tier — no rig cell forces an
   enrichment outage (B's documented position, unchanged by this part). (3) additive envelope — no
   library file in the diff. (4) exactly one policy ticket — `git diff --stat` shows **no**
   `infra/opa/policies/` path in T5 or T6; `opa test` is 367/367 unchanged. (5) nothing
   elevation-/agent-related in `filter`/`bulk` — E6d is the wire proof: the agent's list answers
   **200 with zero rows**, i.e. the leg was skipped app-side, not denied by a residual. (6) the three
   prongs — the wire claim (the preflight asserts `act_chain` presence/absence), the policy deny
   (E6a–c), the app-side leg (E6d + its control). (7) one window — the challenge parameter follows
   the data, and the realm/`step_up.json` cross-reference is in `infra/README.md`. No finding.
4. **Blast radius of the two edits to pre-existing runners.** Read as a diff rather than trusted: both
   changed exactly `mint_token`'s signature (an optional client/secret/otp tail, defaulted so every
   existing call site is byte-identical in behaviour) plus the one line that mints anna. No cell,
   assertion, fixture, ordering or teardown moved, and both matrices were re-run green in two
   orders. The **cause** of the need is part 0's, and is escalated above rather than fixed there.
5. **Cross-ticket composition (T5 ↔ T6).** The miner's frozen contract and the runner's use of it
   agree flag for flag; the one addition (`--print-otp`) was made in T6 and is documented in T5's own
   guide section as well as the README row, so the file has no undocumented surface. The TOTP
   one-time-use retry the miner gained in T5 is exercised for real by T6's drill (the run log shows
   it waiting out a spent window mid-drill) — a T5 mechanism whose only proof would otherwise have
   been a hand-built repro.
6. **Docs vs what actually ran.** The four doc sections were re-read against the run: the flag table
   matches `--help`; the E8 table matches the ten recorded results; `infra/README.md`'s leaf-PUT
   warning matches the runner's assertion; the guide paragraph's cell list matches the collection's
   folders. The `f00d…` registry row states the tier states are the assertions, which is what makes
   the prefix reservation load-bearing. No drift.

**Fixes applied in this review:** the four falsifiers in lens 1 were added during T6's own ★ gate and
confirmed here rather than added here; this pass added nothing further. **Nothing was refactored for
its own sake.**

**Escalations:** one, in *Decisions* above — T1's seeded factor breaking anna's ROPC direct grant.
The harness is repaired inside this part's boundary and everything is green; the realm-level question
and part 0's I5(d) evidence are the maintainer's.

## Commit

`test(step-up-elevation): the step-up matrix, the freshness drill, and the seven C-flips (T6)` — the
new collection + runner, the seven rewritten production-tier cells, the anna-token fix in the two
pre-existing runners, the postman README rows, the TEAM-BASED-AUTHORIZATION e2e paragraph, the
`infra/README.md` matrix section and this STATUS note, on `feature/void3110/step-up-elevation`.
