---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/keycloak
  - area/catalog-service
---

# SPA-CHALLENGE-UX — decomposition

> T1…T6, in order. Each ticket is one focused commit's worth of work, **built collaboratively** —
> the maintainer and the agent on one branch, ticket by ticket, the browser open. The design these
> decompose is [[00-DESIGN]]; the wire contract is [[0033-catalog-provenance-affordance|ADR 0033]];
> the challenge contract they consume is [[0030-step-up-decision-contract|ADR 0030]] §5–7 as
> amended (unchanged by this slice).

## Critical path

```
T1 (backend _provenance) ──┐
T2 (parser + seam) ──► T3 (panel + redirect + restore) ──► T4 (chip + badges) ──► T6 (QA pass)
T5 (persona + seed) ───────┘─────────────────────────────────────────────────────────┘
```

- **T1 and T2 are independent** and may land in either order (T1 is Java, T2 is TypeScript).
- **T3 needs T2** (it consumes `StepUpRequiredError`); **T4 needs T1 and T3** (badges read
  `_provenance`; the chip's window comes from the panel's parser; the passive/elevated states share
  the chip's model).
- **T5 is independent** of T2–T4 and needs only that the realm re-import is acceptable; it is what
  makes the pane cells runnable and is best landed **before T3** so T3's own cells (E10–E12) can be
  driven live as they are built.
- **T6 is last**: the pass runs against the finished branch.
- **T1 is the standalone-value subset**: after T1 the API tells clients which rows are supervised,
  with the SPA unchanged — a safe intermediate state (an unread affordance).

## T1 — the `_provenance` affordance on catalog rows (backend + ADR 0033)

**Goal.** Catalog list items and the single-catalog GET carry an additive, optional
`_provenance: "member" | "supervised"` — server truth for the access path — **absent** whenever the
server did not compute it, on every degrade branch, and identical in meaning on both paths.

**Deliverables.**
- `docs/architecture/adr/0033-catalog-provenance-affordance.md` — written **up front** (the
  repo's convention), status Accepted (planned); the ADR index row.
- `catalog-api.yaml`: `Catalog` gains `_provenance` (`type: string`, `readOnly`, the vocabulary
  `member` | `supervised` **in the description — no `enum:` key**: an inline `enum:` generates a
  per-DTO nested enum type (`ProblemDetail.ErrorCodeEnum`) whose getter cannot satisfy the marker's
  `String getProvenance()` — a compile failure, and no generator option suppresses it), next to
  `_actions`; the schema's `x-implements` list gains the new marker.
- An example-side marker interface (`…catalog.security.CatalogProvenanceCarrier`, name
  indicative) declaring `@JsonInclude(NON_NULL) @JsonProperty("_provenance") String getProvenance()`
  + `setProvenance(String)` — the `Enrichable` absence idiom for a scalar; **`Enrichable` and every
  library type untouched**.
- `CatalogListAuthorizer`: writes the supervised id set — `supervisedIds`, **including the empty
  list** — to a **request attribute** (the `RequestContextHolder`/`SCOPE_REQUEST` idiom the repo
  already uses; no `@RequestScope` bean) **unconditionally in `authorizedPage`, immediately before
  `abacQuery.findAuthorized(...)`** — not inside `auditSupervisedRead`, whose two early returns would
  leave a plain member's page memo-less; return type and every existing branch byte-identical
  otherwise (the five `Page.empty` early returns carry zero rows and never reach the write — stated,
  accepted).
- `…catalog.web.CatalogProvenanceAdvice` (`ResponseBodyAdvice`): for a paged body, stamps each
  `Catalog` from the memo (in the set → `supervised`, else `member`; **present-but-empty → all
  `member`; attribute absent → omit**); for a single `Catalog` **on the GET handler only**
  (`getCatalog` — never on create/update: create's gate is type-level, so a lookup by the new id is
  a guaranteed memo miss and a real round-trip), calls the role supplier's
  `lookup(subject, "catalog", id)` (`Optional<RoleDefinition>`, `throws RoleResolutionException`; a
  request-memo hit under the default-on `opa.abac.resolve-memo.enabled`) and maps
  `supervised → "supervised"`, `membership → "member"`, anything else / any exception → **omit**;
  never throws; touches no other body shape; reads the subject with the same private
  `SecurityContextHolder`/`AbacAuthentication` helper idiom the existing advices carry (no shared
  helper exists; none is added).
- Tests: the advice unit tests (both derivations, every omit branch, serialized-bytes absence); the
  IT cells on `SupervisedListIT`'s personas (`DUAL` mixed, `ANNA` memberless, `MEMBER`; the agent
  branch in `AgentSupervisedLegIT`); the newman cells added to `run-step-up-matrix.sh` as its next
  free block (**the collection's own `E10` `_provenance` cells** — a different namespace from this
  package's case ids, which is why the package numbers them **E30+**), anna + `editor` on the seeded
  pair.
- Doc delta: `REST-API-DESIGN.md`'s affordance/enrichment section gains `_provenance` (one
  semantic, two derivations, absent-when-not-computed; "affordance, not enforcement").

**Acceptance.** **U7**, **U8**, **I1–I4**, **E30**. `./gradlew build` green; `opa test`
untouched (no policy change); local Sonar CLEAN on changed files.

**What NOT to touch.** No library module (`opa-abac-*`). No policy. No `Category`/`Product`
schema. No SPA. The list authorizer's leg logic, anchor rule, degrade branches and audit line are
unchanged — the memo write is the only addition. No new library-side request attribute for the
resolved role (rejected in the design).

## T2 — the SPA challenge seam: parser + `StepUpRequiredError` + the unit runner

**Goal.** The SPA can *see* a step-up challenge: `request()` distinguishes a step-up `401` from a
session-expired `401` and from every other error, and hands callers a typed error carrying the
parsed RFC 9470 parameters — or a plain error when the challenge cannot be followed.

**Deliverables.**
- `example-demo-ui/src/stepup.ts` (name indicative): `parseChallenge(header): Challenge | null` —
  RFC 7235 `auth-param` parsing (quoted-strings + escapes, any order, case-insensitive scheme and
  names, `max_age` numeric ≥ 0), returning `{error, description, acrValues, maxAge}` or `null`;
  `class StepUpRequiredError extends ApiError { challenge }`.
- `api.ts#request()`: capture `body.errorCode` **separately** (the `detail ?? errorCode` line is
  not reordered), read `WWW-Authenticate`, and on `401` + `STEP_UP_REQUIRED` + a parseable header
  throw `StepUpRequiredError`; a bodiless `401` keeps the "session expired" path; an unparseable
  header degrades to the plain `ApiError`. Every `instanceof ApiError` consumer unchanged.
- **vitest** added to `example-demo-ui` (devDependency; `npm test` script; `tsc -b --noEmit` stays
  `lint`) with the parser + classification tests. No DOM/component tests.
- The last-seen `max_age` is written to `sessionStorage` (`stepUp.maxAge` — **not** under the
  `oidc.` prefix, which oidc-client-ts's `clearStaleState()` sweeps on every `signinRedirect`) by
  `request()` — the parser stays pure — the chip's window source (T4 reads it).
- `infra/apisix/init-routes.sh`: `WWW-Authenticate` added to `expose_headers` on the three routes
  (one word × three sites). Not needed by the demo's single-origin paths (dev proxy, packaged nginx)
  — a cross-origin adopter would otherwise get a `null` header and a feature that looks broken with
  no error anywhere; the reason is stated in the SPA README.
- U1's quoted-string-escape case is **defensive** (the emitter's `isSafeParameter` refuses quotes
  and backslashes, so it never produces one) — kept so nobody "simplifies" the parser to the
  emitter; E18 injects the shape by hand.
- `example-demo-ui/README.md`: the challenge seam + the unit runner.

**Acceptance.** **U1–U3**; `npm run lint` + `npm test` green; the existing console builds and
behaves identically for members (no UI change yet).

**What NOT to touch.** No UI components. No `auth.ts` changes (T3). No backend Java. (`init-routes.sh`'s one-word CORS change is the only infra edit.)

## T3 — the locked panel, [Verify], the state-carried location, restoration, one retry

**Goal.** A supervisor who hits the challenge in the console sees it explained in the server's
own words, presses [Verify], re-authenticates at Keycloak with the miner's measured parameters,
lands back where they were, and — if the read is still refused — is told so honestly and never
bounced again automatically.

**Deliverables.**
- `LockedPanel` (in `App.tsx` or `components.tsx`): title, the challenge's `description` verbatim,
  `acr_values` + `max_age` as plain facts, [Verify]; a **passive** variant adding *"verification
  did not unlock production contents"*. Rendered by the categories and products contents areas when
  their loader throws `StepUpRequiredError` (the two `useAsync` call sites — extend `useAsync` to
  keep the error object, or add a sibling hook; the retry-once guard lives in the panel's props,
  not in `useAsync`).
- `auth.ts`: `prompt: 'login'` **moves** from the `UserManager` constructor to `login()`'s
  `signinRedirect({prompt:'login'})`; new `stepUp(challenge, location)` calling
  `signinRedirect({ acr_values: challenge.acrValues, max_age: 0, extraQueryParams: { claims:
  JSON.stringify({id_token:{acr:{essential:true, values:[challenge.acrValues]}}}) }, state: {v:1,
  catalogId, categoryId?, stepUp:true} })` — the miner's recipe, ids only in the state.
- **Before building [Verify]**: settle Keycloak's prompt sequence under `max_age=0` on an existing
  SSO session from the miner's stderr (a mint against a populated cookie jar — the runner's E3 `--no-max-age`
  call); record it in STATUS-03; if the password is re-asked, E10's script says so.
- `App.tsx` callback: `completeLogin()`'s `user.state` is read; a `{v:1, catalogId…}` state becomes
  `Console`'s **initial view** — `getCatalog(catalogId)` → the catalog view; with `categoryId`, a
  new `api.ts#getCategory(catalogId, categoryId)` (the OpenAPI operation exists; the client function
  does not; **one** direct read, not list-then-find) → the category view. Any restored load may
  challenge; **none triggers a redirect** — a challenged `getCategory` leaves the user on the catalog
  view with the (passive) panel. `User.toStorageString()` omits `state`, so `user.state` exists only
  on the callback's page load — do not persist it.
- The passive guard: `user.state.stepUp === true` on this page load ⇒ a `StepUpRequiredError` in
  the restored view renders the **passive** panel; a manual [Verify] clears it. **No code path
  calls `stepUp()` without a click.**
- `example-demo-ui/README.md`: the flow, the state shape, the loop guard, the accepted "cancel loses
  the drill-in" limitation.

**Acceptance.** **E10–E12**, **E18** driven live in the pane on the seeded world (T5), each
recorded in STATUS-03 with what was observed (E10 records Keycloak's prompt sequence under
`max_age=0`); `npm run lint` + `npm test` green.

**What NOT to touch.** No chip, no badges (T4). No backend. `redirect_uri`, the token store, the
on-demand refresh, and the single-origin authority are unchanged. No popup path.

## T4 — the elevation chip and the row badges

**Goal.** The console shows elevation state and the bounded window honestly (learned, never
hardcoded; reactive at expiry), and marks supervised and production rows so the prediction is
visible before the click — amber where the client predicts, never where the server decides.

**Deliverables.**
- `auth.ts#describeUser` (or a sibling `elevationOf(user)`): decode `acr` + numeric `auth_time`
  tolerant of absence; the LoA map `{aal1:1, aal2:2}` app-side (display only).
- The **chip** in the header next to `RoleChips` (the `RoleChips` idiom): the five states of §6 —
  `Elevated · m:ss` (window known: `sessionStorage.stepUp.maxAge`), `Elevated (aal2)` (no window),
  amber `elevation lapsed` (at zero; content untouched), amber `not elevated` (a challenge seen this
  session), hidden otherwise. A 1 s tick; the number is `auth_time + max_age − now`, clamped.
- The **badges** on the catalogs grid + the catalog detail card: `supervised` (grey chip) from
  `_provenance`; `production` (`TagLine`) from `tags.env`; the amber `production · verify to open`
  variant iff supervised ∧ production ∧ chip ≠ elevated. `api.ts` `Catalog` gains
  `_provenance?: 'member' | 'supervised'`. Categories/products inherit the catalog's provenance
  context on drill-in (no per-row derivation).
- The pure seams unit-tested (vitest): the claims decode, the window/chip state, the badge
  predicate.
- `example-demo-ui/README.md`: the chip states, the learned window, the amber rule.

**Acceptance.** **U4–U6**; **E13–E17**, **E19** driven live in the pane and recorded in STATUS-04;
`npm run lint` + `npm test` green.

**What NOT to touch.** No client-side hiding of rendered content at expiry. No `300` (or any window)
in source. No backend. No changes to how members' rows or the existing amber/red idioms render.

## T5 — the demo supervisor: `sup-demo` / `pm-demo`, the seed's supervised block, the registry

**Goal.** A fresh rig tells the whole A/B/C story in the browser: a supervisor persona with a
seeded TOTP, a report, two supervised catalogs (one production, one open) — owned by the seed,
coexisting with every matrix, surviving realm re-imports.

**Deliverables.**
- `infra/keycloak/realm-export.json`: `sup-demo` (`unit-supervisor` + `catalog-viewer`, password
  = username, an `otp` credential in the exported shape anna's uses — its **own** fixture secret,
  labeled public-on-purpose) and `pm-demo` (`catalog-editor`, password = username). **Spike-first**:
  create them on the live rig (admin console/API), `kc.sh export` from inside the container,
  transplant the exported JSON — the credential JSON is never written from memory. **Optionally**
  pin both users' `id` (no user pins one today) so their `sub` survives a re-import; record in
  STATUS-05 whether Keycloak honours the pin — the seed converges either way (find-or-create by
  subject + a REPLACE edge).
- `scripts/postman/seed-demo-data.sh`: a `# --- supervised world ---` block in the seed's own style
  (config gains `CATALOG_SERVICE=http://localhost:28081`; helpers reused): bootstrap `pm-demo` by
  its minted subject and `sup-demo` by the subject **looked up on the Keycloak admin API** with the
  `catalog-directory` service account (`client_credentials` → `GET
  /admin/realms/catalog-demo/users?username=sup-demo&exact=true` → `.id`; `run-team-matrix.sh`'s
  `dora` recipe — Keycloak's direct-grant flow demands an OTP from any identity that has one, so
  `sup-demo` is never minted by the seed); `d311…0002` "Demo Production Catalog" + `d311…0003`
  "Demo Open Catalog" (psql `ON CONFLICT` upsert; the ltree path); a category + product under each
  through the gateway with `pm-demo`'s ROPC token; `Demo Production Team` / `Demo Open Team`,
  `pm-demo` `owner` on both; `POST /internal/bootstrap/reporting-edges {managerId: sup-demo,
  reportIds:[pm-demo]}`; `POST $CATALOG_SERVICE/internal/bootstrap/resource-tags {catalog,
  …0002, {"env":"production"}}` with the response asserted; **`sup-demo` never bound**; idempotent
  on re-run; a preflight that names the down-first re-import when `pm-demo` cannot be minted; the
  closing persona echo lists the two.
- `mint-code-flow-token.py`: **no change** — `--print-otp --otp-secret <secret>` already exists;
  the demo secret is recorded next to `DEFAULT_OTP_SECRET`'s mention in the SPA README/E2E guide so
  the pane cells can call it.
- `scripts/postman/README.md`: the `d311…` registry row (the seed's family incl. `…0001`) and the
  `sup-demo`/`pm-demo` persona carve-out in the reserved-family paragraph. `infra/README.md`: the
  personas + the down-first re-import. `E2E-TESTING.md`: the seeded supervised world as the pane
  precondition.

**Acceptance.** **E31–E33**; both matrices (`run-supervised-scope-matrix.sh`,
`run-step-up-matrix.sh`) still green after the seed ran — on a rig brought up with
**`ENABLE_SPA=1 ENABLE_MCP=1`** together (the step-up matrix's preflight needs the MCP server); the
seed re-run is a no-op.

**What NOT to touch.** `sup-anna` and the whole `sup-*`/`pm-*` matrix family: no edge, no
membership, no grant. No matrix runner's fixtures or teardown. No existing seed identity's roles or
teams. No `d311…0001` semantics.

## T6 — the pane pass, the launch entry, the ratchet, the close-out

**Goal.** The finished branch is validated the way the loop prescribes — a committed case list run
adversarially in the Browser pane, findings ratcheted into durable tests — and the slice closes
with its paper trail.

**Deliverables.**
- `.claude/launch.json`: an **attach** entry for the packaged SPA (`url: http://localhost:9085` +
  `port: 9085`, no command — an attach entry's localhost `url` must match its `port`) beside the
  existing Vite `demo-ui` entry.
- The E10–E19 pass, fresh-eyes, adversarial, against `ENABLE_SPA=1 ENABLE_MCP=1 ./deploy.sh up
  --pods 2` + the seed (both flags on the same `up`); STATUS-06 records
  each finding, its ratchet (newman cell added / QA row added), and the re-run.
- The doc sync that only the finished branch can write: `USER-STORIES.md` (K16 — the console
  story), the roadmap's Phase 10 line (📋 → ✅ on ship), the package index status; the
  `example-demo-ui/README.md` final read-through.
- `/deep-review` before the PR (the collaborative build's layer 3), then the ship commit
  (`docs(spa-challenge-ux): ship — …`, the C precedent).

**Acceptance.** **E20**, **E21**; every T1–T5 acceptance still green on the final branch;
`./gradlew build`, `npm run lint`, `npm test`, both step-up-adjacent matrices, the smoke suite —
all green; Sonar CLEAN.

**What NOT to touch.** No new feature work in T6 — findings become ratcheted tests and fixes to
the surfaces T1–T5 own; anything larger is a recorded follow-up, not a T6 change.
