---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/opa
  - area/spring
---

# STEP-UP-ELEVATION — decomposition

> T1…T6, in order. Each ticket is one focused commit's worth of work. The design these decompose is
> [[00-DESIGN]]; the contract is [[0030-step-up-decision-contract|ADR 0030]] §5–9 **as amended**
> (§Amendments 2026-08-13 — sole-blocker `deny_reason`, the elevation-proof unproven tier, the
> mirrored freshness window, the human-only supervised path).

## Critical path

```
T1 (realm) ────────────────┐
T2 (policy) ──┐            ├──► T5 (miner) ──► T6 (matrix)
T3 (envelope) ┴──► T4 (adoption + wiring)
```

- **T2 and T3 are independent of T1 and of each other** — they may land in any order after work
  starts; T4 needs both (it consumes T2's wire shape and T3's `decide()`).
- **T1 is independent** of T2–T4 and is the only ticket T5 hard-depends on (the miner needs the
  level-2 flow and the seeded TOTP secret to mint `aal2`).
- **T1–T4 are the standalone-value subset (= part 0)**: after T4 the mechanism is complete and the
  rig is in a **safe intermediate state** — the realm can mint `aal2` but nothing deployed requests
  it (ordinary logins land at `aal1`; the SPA sends no `acr_values`), so every supervised
  production read remains a plain-shaped deny until part 1's miner proves the round trip.
- T6 is last: it consumes the miner, the matrix personas, and every prior deliverable.

## T1 — realm: the claims fix, the level-2 TOTP flow, and the seeded factor

**Goal.** The rig's Keycloak mints tokens whose **access token** carries `acr` + `auth_time` on the
interactive code flow, demands TOTP for `acr_values=aal2`, and lets the e2e compute codes from a
fixture secret — all declaratively, from the realm export.

**Deliverables.**
- `infra/keycloak/realm-export.json`:
  - `basic` + `acr` added to `defaultClientScopes` of **both** `catalog-spa` and `catalog-gateway`
    (ADR 0030 §Context's diagnosis: the literal four-name list *replaces* Keycloak's built-in
    assignment, leaving the built-in `auth_time` session-note mapper and `acr` scope assigned to no
    client; re-confirmed on the committed export 2026-08-13).
  - The realm **ACR-to-LoA mapping** `{"aal1": 1, "aal2": 2}` (realm attribute
    `acr.loa.map`, as Keycloak's admin console writes it — **spike-verify, below**).
  - The **browser flow** gains Keycloak's conditional **Level-2 subflow**: *Condition — level of
    authentication* (LoA 2, **max age 300** — mirroring `step_up.json`'s `max_age`; both are
    JSON-hosted values, so the cross-reference lives in the docs: `infra/README.md`'s realm note +
    the step-up guide subsection) + **OTP Form** as the required authenticator.
  - `sup-anna` gains a seeded **`otp` credential** with a fixed, fixture-only secret (committed —
    a demo realm with fixture identities; documented as such next to the credential).
- **Spike-first discipline** (the AGENT-TOOL-AUTHZ T1 precedent): configure the flow + mapping +
  credential on the **live** rig via the admin console/API, run
  `kc.sh export` from inside the container, and transplant the exported JSON shapes into
  `realm-export.json` — the flow-definition and `credentialData`/`secretData` JSON are **never
  written from memory**. Record the exported shapes in `STATUS-01.md`.
- Doc delta: `infra/README.md` gains the realm-change note + the **down-first re-import**
  requirement for any runner that needs the new personas' factors (slice A's precedent).

**Acceptance.** **I5**: `./deploy.sh down && ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up
--pods 2` boots clean (realm re-imports), then a **throwaway probe script written in-ticket**
(the scripted-PKCE shape + the Secure-cookie gotcha are recorded in Mulch `opa-abac-rig-deploy-ops`
mx-afd666/mx-a85001; T5 productionizes it) shows: plain code flow → access token with `acr: aal1`
(or the realm's LoA-1 name) **and numeric `auth_time`**; code flow with `acr_values=aal2`
(essential) + `max_age` → the TOTP prompt → access token with `acr: aal2` and fresh `auth_time`;
a refresh grant preserves `auth_time` (re-assert the measured invariant).

**What NOT to touch.** No application code, no policy, no `scripts/postman` runners (ROPC
`mint_token()` untouched — ROPC still cannot carry `auth_time`, which is fine and stated). No
other realm identity changes: personas, teams, existing client scopes beyond the two additions.
Zero SPA changes.

## T2 — policy: `elevated`, the amended production denies, sole-blocker `deny_reason`, the agent deny

**Goal.** The corpus decides elevation (`loa[acr] >= 2 && now − auth_time ≤ max_age + skew`),
narrows B's production denies by it, emits the structured reason **only** when step-up is the sole
blocker, and closes every supervised **single decision** to agent calls — fail-closed in every
branch.

**Deliverables.**
- `infra/opa/policies/step_up.json` — `{"step_up": {"loa": {"aal1": 1, "aal2": 2}, "max_age": 300,
  "skew": 30}}` (loaded with the policies like `permission_categories.json`; the rig's OPA data
  API accepts runtime overrides of the same path — verified 2026-08-13, T6's drill relies on it).
- In **`category.rego` and `product.rego`** (identically, per-file):
  - `elevated` — the [[00-DESIGN]] §2 shape: `not is_agent_call` +
    `data.step_up.loa[input.subject.attributes.acr] >= 2` + the `time.now_ns()`-based window
    against `data.step_up.max_age + data.step_up.skew`. Undefined on any missing/unmapped input.
  - The **production deny clause** gains `not elevated` (the absent/unproven clause is
    **untouched** — elevation-proof, ADR 0030 Amendment 2).
  - The production+supervised+`not elevated` body factored into **`stepup_denied`** (consumed by
    `denied`), plus **`denied_other`** (every deny except the step-up clause) and the
    **`deny_reason`** rule: emitted iff `stepup_denied ∧ granted ∧ not denied_other`
    (Amendment 1 — the sole-blocker rule).
  - `is_agent_call` — the **presence-test** (`"act_chain" in object.keys(input.subject.attributes)`;
    never a bare truthiness reference — the recorded falsy-claim escape). **The wire claim is
    `act_chain`** (the three `catalog-agent-*` clients' protocol mapper); `actor` exists only as the
    MCP server's internal tool-gate attribute and never travels downstream.
  - The **agent deny**: `denied if { provenance == "supervised"; is_agent_call }`.
- In **`catalog.rego`**: `is_agent_call` + the same agent deny (the file's existing
  `denied`/`not denied` machinery consumes it — verified present 2026-08-13). No `elevated`, no
  tier machinery — the tier lives on the children.
- `opa test` growth in **all three** `*_test.rego` files: the U1–U11 cases (below), incl. one
  **deletion-mutation guard per new clause per file** and the re-measured guards for B's amended
  production clauses.
- Doc delta: none in this ticket (T4 owns the guide subsection; the policy comments carry the
  contract references).

**Acceptance.** **U1–U11** via `opa test infra/opa/policies/` — grown, all green; `opa check --strict`
clean. The window arithmetic is proven with **pinned clocks** (`time.now_ns` overridden in tests),
both boundary directions.

**What NOT to touch.** **`filter` in every file — byte-identical** (nothing elevation- or
agent-related may enter the residual; B's pin, re-asserted by the existing
`test_tier_never_enters_the_filter_residual` in both child files). The absent/unproven clause.
`permissions.rego`, `permission_categories.json`, `team.rego`, `agent_tools.rego` (the tool-gate is
**not** this ticket's surface — ADR 0028's line holds). The bulk entrypoints stay boolean.
**This is the slice's only policy ticket** — any `.rego`/policy-data edit outside it is a
slice-boundary breach.

## T3 — library envelope: `decide()`, the typed reason, and the resilient override

**Goal.** The decision envelope grows the optional, typed `deny_reason` **additively**: every
existing implementor, stub, and consumer compiles and behaves byte-identically; only a caller of
the new `decide()` sees the reason.

**Deliverables.**
- `opa-abac-core`:
  - `DenyReason(String type, String requiredAcr, Integer maxAge)` — record, Jackson-mapped from
    the wire field names (`type`, `required_acr`, `max_age`), unknown fields ignored.
  - `OpaDecision(boolean allow, DenyReason denyReason)` — record; `denyReason` null = plain.
  - `OpaClient.decide(AbacContext)` — **default method** delegating to `allow()` with a null
    reason (the additivity move: no implementor breaks).
  - `HttpOpaClient.decide(...)` override: parses `result.allow` + `result.deny_reason` from the
    same response; a **malformed** reason (wrong types/shape) → `OpaDecision(allow, null)` — the
    reason is dropped, never thrown on, never widening; **`allow:true` + a present `deny_reason`
    (a contradictory document) → the reason is dropped** (an allow is an allow; U13 pins it);
    `allow()` itself delegates unchanged.
- `opa-abac-spring-security`:
  - **`ResilientOpaClient.decide(...)` override — mandatory, not optional** (the default-method
    trap, seam-verified 2026-08-13: without the override the wrapper's inherited default would
    delegate to its own guarded `allow()` and silently swallow every reason): guards the
    delegate's `decide()` with the same CallGuard discipline; **every fail-closed outcome**
    (breaker open, transport failure, retries exhausted) returns `OpaDecision(false, null)` —
    never a fabricated reason (a challenge promises "re-auth fixes this"; during an outage that is
    a lie and a TOTP treadmill).
- Tests (U12–U15): in-process `com.sun.net.httpserver.HttpServer` stub for the HTTP parse cases
  (house convention, no WireMock); a plain-stub implementor proving the default's delegation; the
  resilient override's fail-closed matrix.
- Doc delta: `docs/guides/ABAC-AUTHORIZATION.md` gains the **decision envelope** section (the
  optional `deny_reason`, the three wire shapes from ADR 0030 §6, the fail-closed parse rules).

**Acceptance.** **U12–U15** via `./gradlew :opa-abac-core:test :opa-abac-spring-security:test`; the
**additivity proof**: every pre-existing library test passes **unmodified** (zero test-file diffs
outside the new test classes).

**What NOT to touch.** `opa-abac-core` stays Spring-free (the records are pure Java/Jackson).
`allow()`/`allowAll()` signatures and behavior — byte-identical; `PartialResult`/the compile path;
`opa-abac-spring-data` entirely. No manager changes (T4's). The `OpaAuthorizationManager`
(request-pattern) is **out of scope for the whole slice** — stated, not silent.

## T4 — adoption + wiring: the manager, the 401 emitter, audit, and the agent guards

**Goal.** The step-up deny travels from the policy to the wire: the method-security manager
surfaces it, the library advice mints the RFC 9470 challenge, both audit events emit, the catalog
service ingests the three claims, and the supervised **list** leg closes to agents app-side.

**Deliverables.**
- `opa-abac-spring-security`:
  - `OpaPreAuthorizeAuthorizationManager` calls `decide()` on its **single-decision** path (both
    instance and type-level shapes); an `OpaDecision` with a reason returns
    **`StepUpRequiredDecision`** (new class, `extends AuthorizationDecision` — subclassable,
    seam-verified via `javap` on spring-security-core 7.0.6 — `granted=false`, carrying the
    `DenyReason` **plus the resource type+id and the governing-root id**, copied by the manager
    from its resolved check: **log-only fields** for the audit event that never re-enter a
    decision); every other outcome returns today's `AuthorizationDecision` byte-identically.
    The bulk/batch and enrichment paths are untouched (boolean, per T2/T3 pins).
  - `AbstractProblemAdvice`: the existing `@ExceptionHandler` branch inspects
    `AuthorizationDeniedException.getAuthorizationResult()` (seam-verified, same `javap`); a
    `StepUpRequiredDecision` with a **complete** reason maps to **401** +
    `WWW-Authenticate: Bearer error="insufficient_user_authentication", error_description=…,
    acr_values="<reason.requiredAcr>", max_age="<reason.maxAge>"` + a problem+json body with the
    new **`LibraryErrorCode.STEP_UP_REQUIRED`** (`ApiErrorCode` is the interface — the library's
    constants live on the `LibraryErrorCode` enum, verified); a reason with any null field → the
    existing **plain
    403** (no half-formed challenge — the §7 loop guard); every non-step-up denial → the existing
    403 byte-identically.
  - The audit logger **`opa.abac.audit`** (SLF4J, structured key-value): `STEP_UP_CHALLENGED`
    emitted in the advice at 401-mint — the subject from the security context, the resource
    type+id + governing root from `StepUpRequiredDecision`'s log-only fields, plus the challenge
    params (**no** `acr`/`auth_time` — the subject is precisely *not* elevated at challenge time);
    `SUPERVISED_PRODUCTION_READ` emitted in the manager iff **granted** ∧
    `provenance == "supervised"` ∧ the target's `root_attributes.env` **contains** `"production"`
    (normalized scalar-or-array, mirroring the policy's `root_env_values` — the cardinality twin)
    — **elevation is implied by the allow and never re-derived app-side** (no Java copy of
    loa/max_age/skew; invariant 7); payload: subject, governing root, resource, access path, plus
    `acr` and `auth_time` logged **verbatim** from the subject attributes (ADR 0030 §8's list).
    **Emission never affects the decision**: the emit path catches and drops its own exceptions.
- `example-catalog-management-service`:
  - `application.yml`: `opa.abac.subject.attribute-claims: [acr, auth_time, act_chain]` (the
    existing `SubjectClaimsConfig` ingestion — seam-verified: `objectMapper.convertValue(node,
    Object.class)` preserves JSON number types, so `auth_time` stays numeric for the rego
    arithmetic; `act_chain` is the **wire** delegation claim — the MCP-internal `actor` name never
    reaches this service).
  - `CatalogListAuthorizer`: the **supervised-leg agent guard** — when the subject's attributes
    carry the `act_chain` **key** (presence, not truthiness), the supervised leg is **skipped**,
    reusing the existing supervised-source-degrade shape (agents degrade to membership-only; a
    pure supervisor's agent call sees the empty page).
  - `catalog-api.yaml`: `STEP_UP_REQUIRED` added to the closed `errorCode` enum (the additive-enum
    discipline; the 409/`TAG_OPERATOR_MANAGED` precedent).
- Tests: U16–U20 (manager decision shapes; advice 401/403 matrix incl. the partial-reason
  fallback; audit emission + the swallowed-exception case; extractor type preservation through the
  config) and I1–I4 (the 401 over MockMvc with a recording OPA stub carrying `deny_reason`; the
  plain-deny 403 contrast; the leg guard under Testcontainers; the audit events via a log captor).
- Doc delta: `docs/guides/TEAM-BASED-AUTHORIZATION.md` gains the **Step-up elevation** subsection
  (T6 appends only its e2e paragraph — subsection-level ownership, the B lesson).

**Acceptance.** **U16–U20** + **I1–I4** via `./gradlew build` (all modules; Testcontainers; every
pre-existing test unmodified-green). Local Sonar CLEAN on changed files.

**What NOT to touch.** No realm, no policy files (T2 owns the corpus — T4 consumes it through the
stub, not by editing it). No `example-mcp-server` changes (the tool-gate/roster is a hint; the
closure is target-side). No `example-demo-ui` (the SPA follow-up is out of slice). No
`opa-abac-spring-data`, no `HierarchicalAuthorizer` (tier-unaware, documented — ADR 0032
§Consequences). The request-pattern `OpaAuthorizationManager` stays boolean.

## T5 — the code-flow token miner

**Goal.** A deterministic, dependency-free way to mint `aal1` and `aal2` access tokens for the
e2e — the non-ROPC token path ADR 0030 §Context made a first-class deliverable.

**Deliverables.**
- `scripts/postman/mint-code-flow-token.py` — **stdlib-only** python3 (`urllib` + `http.cookiejar`
  + `html`/regex form parse + `hmac`-based RFC 6238 TOTP): mints via the scripted PKCE code flow
  against the rig's Keycloak; flags for persona, `--acr aal2` (adds `acr_values` **essential** +
  `max_age` and answers the TOTP prompt from the seeded fixture secret), `--no-max-age` (the
  loop-prevention negative's tool), and `--cookie-jar <file>` (a persisted `http.cookiejar` so a
  later invocation **reuses the Keycloak SSO session** — cross-invocation SSO is what E3 and
  E9(c) measure; without it every invocation is a fresh login and the "same stale auth_time"
  claim is untestable). Prints the access token to stdout; diagnostics to stderr.
  Carries the probe's hard-won gotcha as a comment and a workaround: Keycloak's cookies are
  `Secure`-flagged, and an http client that silently withholds them over plain http produces the
  misleading "Restart login cookie not found" 400 — the miner forces the cookie header.
- Doc delta: `docs/guides/E2E-TESTING.md` gains the **code-flow token path** section (when ROPC
  is structurally wrong — `auth_time` — and how the miner is used; the in-network caveat contrast:
  the miner talks to Keycloak's **host** port and the gateway validates the in-network issuer, so
  the miner mints against the same issuer the ROPC helper uses — verified in E9).

**Acceptance.** **E9**: against the up rig (post-T1 realm), the miner mints (a) an `aal1` token whose
decoded payload carries numeric `auth_time` and no/`aal1` acr, (b) an `aal2` token via the TOTP
step with `acr: aal2`, (c) with `--no-max-age` **and the same `--cookie-jar`** after (b): a token
whose `auth_time` equals (b)'s (the SSO-reuse proof the E3 cell rides). Tokens from the miner authenticate through the gateway
(a 200 on a member read — the issuer parity check).

**What NOT to touch.** `mint_token()` and every existing runner/collection — untouched. No
`requests`/venv dependency (the maintainer's shell runs it bare). No realm changes (T1 owns them).

## T6 — e2e: the step-up matrix, the freshness drill, and non-regression

**Goal.** The round trip proven on the rig, on exact status codes, header params, body codes, ids
and log lines — plus the drill that shows an elevation expiring, and the enumerated proof that
nothing regressed.

**Deliverables.**
- `scripts/postman/step-up-matrix.postman_collection.json` +
  `scripts/postman/run-step-up-matrix.sh` — the E1–E7 cells (below), fixture-registry-compliant
  (dedicated id prefix; every `pm.test` callback throws — the E2E-TESTING assertion-style
  convention). The runner: **`./deploy.sh down` first** (the realm changed — T1's re-import
  discipline), restarts OPA before minting (T2 changed the corpus), polls a **real decision**
  (never `/health`), mints via `mint-code-flow-token.py`, and restores everything (incl. the drill
  override) via an EXIT trap.
- The **freshness drill** inside the runner — four steps, in order: (1) override the **leaf
  path**: `PUT /v1/data/step_up/max_age` with body `5` (**never** a whole-document PUT — OPA's
  data PUT is create/overwrite, so `PUT /v1/data/step_up {"max_age":5}` would clobber `loa`/`skew`
  and produce a vacuous 401 with zero waiting; mechanism verified 2026-08-13); (2) the **positive
  control**: a fresh elevation still 200s under the override (proves `loa` survived); (3) wait
  **> max_age + skew = 35s**, assert the previously-elevated token now answers 401; (4) run E3
  inside this shrunk window (elevate → wait it out → `--no-max-age` + the cookie jar → still 401),
  then **restart OPA** to restore the file-loaded data (the trap's job).
- The **agent cells**: through the MCP surface (`ENABLE_MCP=1` flavour), an **agent-client**
  token (ROPC via a `catalog-agent-*` client — it carries `act_chain` and can never carry
  `auth_time`; the "elevated agent" combination is unmintable on this rig and lives in U10's
  constructed-input `opa test`) for **anna's subject** in tool calls against production **and**
  non-production supervised content → plain 403, no `WWW-Authenticate`; `list_catalogs` as the
  agent → membership-only (empty for anna). If any enumerated matrix preflight-requires a superset
  flavour, run the whole set on it (the B lesson).
- **The C-flip amendment to B's matrix — enumerated, not discovered.** C's sole-blocker
  mechanism **changes seven production-tier cells by design**: anna's unelevated production child
  reads flip from a plain 403 to **401 + `WWW-Authenticate` + `STEP_UP_REQUIRED`** — the affected
  cells are production-tier **E2a–E2d, E4b, E4c, and E5d** (anna is `granted`; her token is not
  elevated; no other deny fires ⇒ the challenge is the correct answer). This ticket **rewrites
  exactly those seven cells** in `production-tier-matrix.postman_collection.json` to assert the
  401 shape (annotated in-cell as C's amendment — a *stronger* assertion than the plain 403), and
  no others. Everything else in B's matrix (E1, E2pre, E3, E5a–c, E5e–g, E6, E7, E4a/E4d/E4e) and
  **all** of A's supervised-scope matrix is C-invariant and must pass unmodified.
- **Non-regression enumeration** (each runner named, run, and recorded in `STATUS-06.md` with
  run/skip + reason): `run-supervised-scope-matrix.sh` (A's cells — anna's ordinary reads are
  **unchanged**: `aal1` humans keep everything B gave them on non-production content),
  `run-production-tier-matrix.sh` (B's cells with the seven enumerated 401-flips + E5f/E5g
  unchanged), `run-tests.sh`, `run-filter-matrix.sh`,
  `run-hierarchy-list-matrix.sh`, `run-isolation-matrix.sh`, `run-action-enrichment-matrix.sh`,
  `run-agent-tool-matrix.sh` (the roster/deny cells still hold with the new claims ingested),
  `run-resource-resolution-matrix.sh`, `run-tag-matrix.sh`.
- Doc deltas: the `scripts/postman/README.md` registry row + runner row; the
  `TEAM-BASED-AUTHORIZATION.md` e2e paragraph (append-only — T4 owns the subsection);
  `infra/README.md`'s step-up matrix section.

**Acceptance.** **E1–E8** green through the gateway on the flavours each requires; the full
non-regression enumeration green; `STATUS-06.md` carries the run/skip record.

**What NOT to touch.** No policy edits (a failing cell is diagnosed, not patched around — T2 is
the only policy ticket). No realm edits beyond re-import. No miner changes that break E9's
recorded contract (extend, don't repurpose). Existing collections: `supervised-scope` — comments
only, **no cell rewrites** (A's behavior is C-invariant); `production-tier` — **exactly the seven
enumerated C-flip cells** (E2a–E2d, E4b, E4c, E5d) may be rewritten to the 401 shape, each
annotated as C's amendment; every other cell in every other collection is untouchable.

## Cross-cutting acceptance

- `./gradlew build` green at every checkpoint (all modules; Testcontainers; `ddl-auto: validate`);
  `opa test infra/opa/policies/` green at every checkpoint — unchanged until T2, grown after;
  local Sonar CLEAN on changed files for every `.java` ticket.
- Every pre-existing library test **unmodified and green** through T3–T4 (the additivity proof).
- The failure classes stay distinct end to end: missing/unmapped claims ⇒ not elevated ⇒ the
  production deny holds (never a 5xx); a malformed or outage-shaped reason ⇒ a **plain** deny at
  the exact layer it arose; a half-formed challenge is never emitted; audit failure changes no
  decision; agent calls degrade to membership-only on the list leg and deny plainly on single
  decisions.
- Clean-room: no consumer names; commit identity `Void3110 <void31102025@gmail.com>`.
