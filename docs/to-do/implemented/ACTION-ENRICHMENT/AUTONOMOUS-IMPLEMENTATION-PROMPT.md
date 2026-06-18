---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/spring
  - area/opa
---

# Action enrichment (affordance metadata) — autonomous implementation prompt

> **What this is.** A self-contained prompt for implementing Phase 6 (action enrichment — the `_actions`
> affordance map on returned resources) autonomously, ticket by ticket, with an architecture-review gate
> and a checkpoint after each ticket. The design and work list it refers to live alongside it in this
> folder.
>
> **Before you run it:** create the branch — `git checkout -b feature/void3110/action-enrichment` off a
> clean `main`. Confirm `git config --local user.email` is `void31102025@gmail.com`. Then paste the
> **PROMPT** section below to the agent.

---

## PROMPT

You are implementing **action enrichment — affordance metadata** (Phase 6) on branch
`feature/void3110/action-enrichment`.

**The problem.** Enforcement answers "may I do this *one* action?" (`@OpaPreAuthorize`); data filtering
answers "*which rows*?" (`AbacQueryService`). Neither tells a UI, for the resource(s) it received back,
**which actions are available on each** — so a frontend renders every button and lets some `4xx`, or
hardcodes its own guess. You are adding the **read-side affordance mechanism pinned in ADR 0016**: a
library `ResponseBodyAdvice` recognizes returns whose DTOs implement a marker `Enrichable`, computes each
resource's `_actions` map (bare-verb keys → `true`/`false`) from the resource's **resolved attributes**
(the 5.97 request-scoped cache) via **one `allowAll` batch call per resource type**, and writes it inline.
The headline: a UI shows exactly the buttons the user can use — affordance that *mirrors* enforcement
(tags + hierarchy), honestly reporting `delete:false`. **Scope boundary:** this is **affordance, NOT
enforcement** — it never blocks a request; the three enforcement layers of ADR 0006 are untouched.
Single-resource **and** list/page are both in scope. **Zero `OpaClient` change, zero Rego change** (reuse
the `allowAll`/`bulk` primitive verbatim). Adopters: catalog (all three types) **and**
user-management-service. `Membership` enrichment and runtime-pluggable verb registries are explicitly NOT
in this slice.

Implement the core work directly. Do not delegate the implementation to a sub-agent. Sub-agents are
welcome for **read-only scouting** (e.g. "find every reference to `AbacResourceCache` across the suite")
and for **log-noisy validation** (e.g. run the newman matrices and report back only the failure summary)
— their findings come back to you; the code, tests, and docs are written in this loop.

### Read before you start (in order)

1. `ACTION-ENRICHMENT.md` (this folder's index) — what this slice delivers, the file glossary, the
   ticket status table, the critical path, conventions.
2. `00-DESIGN.md` — the mechanism (§2), the **verb sets verified-not-assumed** (§3), the **behavior
   matrix** (§4 — the `_actions` cells incl. the degrade rows), what this slice does NOT change (§5), the
   proof obligations (§6), and the forks already closed (§7 — do not reopen them).
3. `01-DECOMPOSITION.md` — the seven tickets, each with Goal / Deliverables / Acceptance /
   What-NOT-to-touch, **plus the three pinned contract semantics** (omit-on-failure; affordance-honesty;
   cache-snapshot-not-verdict) and the **verified verb-set table**. **This is your work list.**
4. **The pinned decisions** — ADR `docs/architecture/adr/0016-action-enrichment-affordance-metadata.md`
   (every fork, with rejections — inline `_actions` via `x-implements` + an explicit `readOnly` property;
   the per-type sub-interface as the registry; reuse `allowAll`; the `AbacResourceCache`→core relocation
   + list write-through; the omit-on-failure degrade; affordance honesty). Skim ADR `0013` (the 5.97
   resolver/cache + the cache-as-snapshot invariant this reuses), `0005` (the `allowAll`/`bulk` batch
   primitive), `0006` (the enforcement layers enrichment is *not*), `0012` (the page envelope `_actions`
   rides on), `0015` (the team control-plane verbs + the Java escalation gates behind the honesty exclusion).
5. `10-QA-TEST-CASES.md` — the U/I/E/D cases your work must satisfy, the pinned-contract table, and the
   fail-closed checklist.
6. **Context you will be checked against** in the review gate (step 5): the shipped
   [[RESOURCE-RESOLUTION]] (`AbacResourceCache` + `ResourceResolutionSupport` — the cache you relocate and
   the governing-root role rule the advice mirrors *exactly*; the `ObjectProvider` opt-in starter wiring),
   [[DATA-FILTERING]] (`OpaClient.allowAll`/the `bulk` rule — the batch primitive you reuse verbatim; the
   `AbacQueryService` seam T3 extends), [[HIERARCHY-LIST-FILTER]] (the nullable-collaborator-via-
   `ObjectProvider` + the list composition the write-through sits beside), [[REST-API-REFINEMENT]] +
   [[PAGINATION-ENVELOPE]] (the `@RestControllerAdvice` idiom + the `PageEnvelope`/`<Resource>Page` shape
   the advice detects), and `docs/guides/CONCURRENCY-AND-LOCKING.md` (the decide-under-protection
   invariant — relevant to the cache-as-snapshot reasoning). E2E details: `docs/guides/E2E-TESTING.md`.
7. Root `CLAUDE.md` — the **IP boundary** (clean-room: original names only) and the **commit identity**
   rule (`Void3110 <void31102025@gmail.com>`).
8. `infra/README.md` — the local rig (needed for the e2e ticket), incl. the **in-network token caveat**
   and `./deploy.sh build` to force new app code into pods. ~~**No OPA restart needed** — this slice
   changes **zero Rego**.~~ *(Corrected during T6/deep-review: the slice DID add the `bulk` entrypoint to
   `catalog`/`product`/`team` rego, so **OPA must reload** on first pull — the matrix runner restarts it
   itself. See ADR 0016 §6.)*
9. **Prime Mulch:** `ml prime opa-abac` and `ml search "allowAll batch enrichment cache governing root"`
   (records `mx-4e6071` the `AbacQueryService` seam + per-row `allowAll` context build [T3], `mx-a932a0`
   /`mx-c39a0a` the `allowAll`/`bulk` batch primitive abstract-not-default + fail-closed [T2], `mx-e842ba`
   governing-root role + ancestors-in-input the advice mirrors, `mx-9dbdc1`/`mx-cdbfe4` opt-in starter
   wiring via `ObjectProvider` [T4], `mx-ecca43` the nullable-collaborator list composition [T3] are
   directly relevant).

### Per-ticket loop (tickets T1 → T7, IN ORDER; T2 and T3 are parallel after T1, both before T4)

For each ticket do ALL of the following, in order, and **STOP at the checkpoint before the next**:

1. **Prime for the files you're about to touch.** `ml prime --files <path>`, and re-read that ticket's
   section in `01-DECOMPOSITION.md`.

2. **Build the deliverables** exactly as `01-DECOMPOSITION.md` lists them — exact classes, packages
   (`dev.dmitriikonovalov.opaabac.{core,security,security.web,data.filter,autoconfigure}` for the library;
   `dev.dmitriikonovalov.example.catalog.*` / `…example.usermgmt.*` for the apps), the `x-implements` +
   `_actions` schema blocks. Match the surrounding code's naming and idioms. **Clean-room:** no
   proprietary names anywhere.

3. **Write/extend the tests** for the ticket (the relevant U/I/E cases from `10-QA-TEST-CASES.md`). The
   advice unit tests drive `beforeBodyWrite` with stub `Enrichable` DTOs + a programmable `OpaClient`
   (in-process `com.sun.net.httpserver.HttpServer` stub — no WireMock) + a pre-populated
   `AbacResourceCache` double; the catalog/user-mgmt ITs run against **real Postgres via Testcontainers**
   (never H2) with the context-aware OPA stub. **No `opa test` change** (zero Rego).

4. **Compile + run unit tests until green.** `./gradlew :<module>:test` (and `./gradlew build` for the
   example/IT/codegen tickets). Fix-until-green.

5. **★ ARCHITECTURE REVIEW + REFACTOR — the gate before integration/e2e validation.** Once unit tests
   pass, do NOT advance yet. Run a focused self-review, then refactor and re-test:
   - **Fail-closed check — the slice's load-bearing invariant, stated concretely: enrichment omits
     `_actions` on EVERY failure (a `bulk` error/timeout, a cache miss for a row, an ancestor- or
     role-resolution failure) — it NEVER emits a fabricated or all-`false` map; a present map is always
     complete (every `abacActions()` verb keyed, real verdict) and absent means could-not-compute. There
     must be no branch that synthesizes a verdict when OPA didn't answer.**
   - **Security check — name the widening that would matter for this ticket and state why it cannot
     happen: an all-`false` map masquerading as a deny (a convention-inverting client reads it as "show
     everything" — omission removes the footgun); a verb enumerated that OPA does not fully decide (a
     team Java-co-gated verb reading `true` when the escalation gate would reject — excluded by design);
     the cache read as a verdict rather than a snapshot (presence ≠ authorized — every verdict is fresh);
     enrichment leaking an attribute or internal detail into the map; the advice altering the handler's
     status or body beyond setting `_actions`.**
   - **Concurrency / idempotency check — the cache is read as an attribute snapshot taken at gate/query
     time, never re-resolved in the advice (no drift between the rows shown and the map); enrichment is
     a pure read with no mutation and is naturally idempotent; the list write-through writes the same
     instance the query returned and never changes which rows are returned (`CONCURRENCY-AND-LOCKING.md`
     — decide/act on the same snapshot).**
   - **Wiring check** — every seam this ticket adds (the relocated `AbacResourceCache` consumers, the
     `Enrichable` marker, `ActionEnrichmentAdvice`, the list-path write-through, the
     `opa.abac.action-enrichment.enabled` property + bean conditions, each `<Type>Enrichable`
     sub-interface, the `x-implements`/`_actions` schema entries) has a **named consumer** and a test
     through its **non-happy path** (the omit-on-failure branch, the kill-switch off-state, a cache miss);
     zero call sites = the ticket is not done.
   - **Boundary / additivity check — `opa-abac-core` stays Spring-free (the relocated `AbacResourceCache`
     + `Enrichable` carry no Spring import); `opa-abac-spring-security` gains no dependency on
     `opa-abac-spring-data` (the advice sees only core types + the marker); `OpaClient` is unchanged
     (`allowAll` reused verbatim); name the byte-for-byte-unchanged surfaces (every `@OpaPreAuthorize`
     annotation, the gate, the `findAuthorized` decisions + return values, pagination, the Rego policies)
     and the one mechanical cost (the `AbacResourceCache` import sweep, landed in the same commit as T1).**
   - **Module-layer separation — the marker + relocated cache interface in core; the advice in
     spring-security; the list write-through in spring-data; the composition + kill-switch in the
     starter; the sub-interfaces + schema in the examples. No layer reaches across.**
   - **Pattern-reuse check — the governing-root role rule mirrors `HierarchicalAuthorizer`/5.97 verbatim;
     the `allowAll` call reuses the Phase-5 primitive (no new `OpaClient` method); the starter
     conditionals mirror the 5.97/5.5 `ObjectProvider` opt-in wiring; the advice mirrors the ADR-0011/
     0012 `@RestControllerAdvice` idiom — no novel infrastructure.**
   - **SOLID / decomposition** — cohesive (SRP), depends on interfaces (DIP); anything to split/simplify?
   - **Apply** the refactoring the review surfaces, then **re-run the unit tests** to confirm green.
   - Write a short note of what the review found + what you refactored into `STATUS-0N.md`. If it found
     nothing substantive, say so explicitly — **do not invent churn.**

6. **Integration / e2e validation (MANDATORY for the relevant tickets).**
   - T5: `./gradlew build` (all modules + the catalog ITs against real Postgres + OpenAPI codegen) —
     `ActionEnrichmentIT` (the honest-`false` map, the deep-product governing-root cell) +
     `ActionEnrichmentListIT` (the no-second-SELECT write-through proof). Fix-until-green.
   - T6: bring the rig up (`./profile.sh up`; `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up
     --pods 2`; `./deploy.sh build` for fresh images; mint tokens **in-network**), then `cd
     scripts/postman && ./run-action-enrichment-matrix.sh` **and every existing `run-*.sh` matrix**.
     Honor the in-network token caveat. Fix-until-green.

7. **Update documentation (after each ticket).** Tick the ticket in the `ACTION-ENRICHMENT.md` status
   table; record real values/decisions in `STATUS-0N.md` (the relocated-cache touched-file list [T1], the
   generated `_actions` accessor name + the codegen config that produced it [T5], the team
   ungated-`getTeam` produced behavior [T6], every existing matrix's green confirmation [T6]). The ticket
   that finalizes the guide topic writes `docs/guides/ACTION-ENRICHMENT.md` and reconciles
   [[ABAC-AUTHORIZATION]] / [[PARTIAL-EVALUATION-FILTERING]] / [[REST-API-DESIGN]] / `infra/README.md` /
   [[E2E-TESTING]] (T7). Root/project `CLAUDE.md` only if a new build/run step matters.

8. **Mulch expertise check (after each ticket).** `ml prime`/skim, then record any genuine reusable
   insight (`ml record opa-abac --type <pattern|decision|failure|reference> …`) and `ml sync`. **Before
   `ml sync`, `git restore --staged .`** so the sync commit touches `.mulch/` **only** (the swept-staged
   trap). Skip recording only if nothing is non-obvious.

9. **Commit** — one focused commit on this branch (code + tests + docs + the `STATUS-0N.md` note
   together). Identity `Void3110 <void31102025@gmail.com>`. Conventional subject
   `feat(action-enrichment): <ticket summary>` (or a narrower `refactor(core)` / `feat(spring-security)` /
   `feat(spring-data)` / `feat(starter)` / `feat(example)` / `test(e2e)` / `docs(…)` scope). A
   `Co-Authored-By: Claude` trailer is welcome.

10. **CHECKPOINT — STOP and report.** Summarize what shipped, paste the unit + integration/e2e summary,
    **summarize the review findings + the refactoring you applied** (step 5), list docs updated, and note
    any open question you resolved. Then proceed to the next ticket. **Do not batch tickets without a
    checkpoint.**

### Permissions / autonomy granted (do these WITHOUT asking)

- Create/modify library code, example-catalog + example-user-mgmt code, the OpenAPI specs, tests, docs in
  this folder + the guides, the `scripts/postman/` suite, and Mulch — all on this branch.
- Stand up / tear down / reseed the local rig (`./profile.sh`, `./deploy.sh`, `ENABLE_OIDC=1
  ENABLE_USER_SERVICE=1 …`); reset fixtures; rebuild images; drop/recreate the **local** schema if needed.
- Fix any issue your own validation reveals (compile, unit, IT, e2e, codegen, refactor). Iterate until green.
- Commit per ticket on this branch.

### Hard rules

- **Fail-closed is the load-bearing invariant — no path returns a wider/fabricated affordance on an
  error than on success:** any failure (bulk error, cache miss, ancestor/role failure) → **omit
  `_actions`** for the affected rows, never an all-`false` or synthesized map; a present map is complete
  and honest; absent means could-not-compute.
- **Do NOT push, open PRs, or touch `main`.** Local + this branch only. The maintainer pushes.
- **Clean-room IP boundary** — never introduce proprietary names, package names, comments, or source.
- **Report at every checkpoint and continue.** Don't batch tickets.
- **The architecture-review + refactor step (5) is NOT optional and happens BEFORE integration/e2e
  validation** — unit green → review → refactor → re-test → then ITs/e2e. Document what it found.
- **Fix-until-green within the ticket.** Only STOP mid-ticket if genuinely *blocked*: the same root cause
  survives ≥3 focused attempts, OR a design decision the docs don't cover is needed, OR a local
  prerequisite is unrecoverable. (The one foreseeable micro-decision — the team ungated-`getTeam`
  behavior — is **pre-decided** in T6: register a resolver, enrich what's cached, omit the rest, never
  re-resolve in the advice. Do not stop to ask it.)
- **Slice-specific invariants — never trade these away:**
  - **Affordance ≠ enforcement.** The advice never blocks a request; a present map is advisory; the real
    gate decides independently. Every `@OpaPreAuthorize` annotation and the gate stay byte-identical.
  - **Omit, never fabricate.** `_actions` present ⇒ a complete, real per-verb verdict; absent ⇒ enrichment
    could not be computed. No all-`false`-on-failure, ever.
  - **Only fully-OPA-decided verbs are enumerated.** The team set is `[list-members, add-member,
    remove-member]`; the Java-co-gated `change-role`/`define-roles`/`transfer-ownership` are excluded.
    The catalog/product sets exclude `assign-tags` (verified — no such endpoint); category keeps it.
  - **The cache is an attribute snapshot, never a verdict.** Read for attributes only; every verdict is
    computed fresh from `allowAll`; the gate still never reads the cache; the list path writes survivors,
    never denied rows.
  - **Reuse `allowAll` verbatim** — no new `OpaClient` method, no Rego change; the advice owns the P×V
    flatten/refold; one `bulk` call per resource type.
  - **`_actions` is additive + `readOnly`** — never accepted on input; enriched schemas only; the
    kill-switch off-state and any non-`Enrichable` response are byte-identical.
  - `AbacResourceCache` relocates to core as a **pure move** (every consumer recompiles in the T1 commit;
    zero behavior change). `AbacQueryService`'s authorization decisions + return values are byte-identical
    (the write-through only adds a cache write).
- **`opa-abac-core` stays Spring-free**; **`opa-abac-spring-security` gains no dependency on
  `opa-abac-spring-data`**.
- **No schema (DB) change, no Rego change** — `ddl-auto: validate` boots clean; `opa test` stays green and
  unmodified. The only OpenAPI change is the additive `x-implements` + `readOnly` `_actions` per enriched schema.

---

## Operator notes (not part of the prompt)

- **The headline tickets are T5 + T6.** The catalog IT where one read-only subject's `_actions` shows
  `view:true, update:false, delete:false` on a real row (I3 — the honest map enforcement-mirroring) and
  the e2e viewer-vs-editor key-by-key contrast (E1) + the omit-on-failure live cell (E4) justify the
  whole design. Make the e2e assert the **map contents**, not just that `_actions` exists.
- **The fail-closed edge to eyeball:** the **fabricated map**. The single most dangerous bug shape is a
  failure (bulk error / cache miss) that emits an all-`false` (or any synthesized) `_actions` instead of
  omitting it — it *passes* a naive "does `_actions` exist" test and silently lies, and a
  convention-inverting client ("show unless explicitly false") would render everything. Every
  `STATUS-0N.md` for T2/T5/T6 must state explicitly that no failure path emits a map. Second edge: a verb
  enumerated that OPA doesn't fully decide (a team Java-co-gated verb) — affordance would over-promise and
  the UI button would `4xx`.
- **Standalone-value subset:** T1 + T2 + T3 + T4 land the complete, dormant library mechanism (the marker
  + advice + cache feed + kill-switch) even before any app adopts (T5/T6) — opt-in, zero behavior change
  for any app without an `Enrichable` DTO.
- **Rig / e2e specifics:** mint tokens **in-network** (APISIX validates issuer `keycloak:8888`); ~~no OPA
  restart needed (zero Rego change — unlike most prior slices)~~ *(corrected: the slice adds the `bulk`
  rule to `catalog`/`product`/`team` rego, so **OPA must reload** — the runner restarts it itself)*;
  `./deploy.sh build` to force new app
  images; the new matrix seeds through the user-service bootstrap like the existing matrices; the
  user-mgmt `getTeam` is **ungated**, so its `_actions` is expected **absent** (the cache-miss degrade) —
  that is a *correct* asserted outcome, not a bug.
- **The OpenAPI-codegen fit is resolved in T5, not deferred:** confirm the generator emits
  `@JsonProperty("_actions")` + a `getActions`/`setActions` matching `Enrichable`; if the default naming
  differs, pin the generator config in T5 and record it in `STATUS-05`. This is the one "open mechanism"
  the design flagged — it lands as a concrete, recorded codegen result.
- **CI does not run the rig yet** — the newman matrices are a local/manual gate; a compose-up→newman CI
  job is a tracked follow-up.
- **Context management** — if the window grows long mid-run, finish the ticket, stop at its checkpoint,
  and resume in a **fresh session** (the ticket status table + STATUS notes are the handoff); sub-agents
  are for scouting/validation only, never the implementation.
- **Workflow-as-artifact:** keep this prompt verbatim; the `STATUS-0N.md` notes record each ticket's
  outcome. Move the folder to `docs/to-do/implemented/` on ship (T7).
