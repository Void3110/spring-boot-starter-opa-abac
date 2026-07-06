# Phase 7.0.5 — Baseline Security Review

- **Date:** 2026-07-06
- **Commit:** `b7ad6a7` (`b7ad6a7cd0bb7c472651fe167df722e286afb926`, 2026-07-06 16:52:54 +0300)
- **Scope (8 angles):** Rego policy corpus · control-plane privilege escalation (role authoring / self-service) · tenant isolation · fail-closed edges · gateway / deploy config · demo-UI token handling · secrets · dependencies (CVE currency).
- **Method:** Multi-agent fan-out (one auditor per angle) → adversarial verify (each survivor re-argued against code, ADRs, tests) → live probes on the SPA rig + OPA 1.10.1 partial-evaluation (`opa eval --partial`) for the authorization findings → cross-angle synthesis pass.
- **Posture:** **Report-only.** This is the pre-publish baseline. **No fixes are applied here** — every fix lands on a follow-up branch (triage list at the end).

---

## Executive summary

| Severity | Count |
|----------|-------|
| High     | 1 |
| Medium   | 0 |
| Low      | 3 |
| Info     | 1 |
| **Total** | **5** |

Note: two findings were entered by the fan-out at higher severities (a "medium" SPA-token finding and a second dependency-scope item) and were **adjusted down** by adversarial verify / probe reasoning — SPA tokens medium→low, starter dependency-leak low→info. The counts above reflect the **adjusted** severities. Nothing was refuted at runtime; the "Refuted at runtime" section is therefore empty.

**The single most important thing:** `catalog.rego`'s list-residual rule `filter` (catalog.rego:155–165) omits the `filter_tags_satisfied` conjunct that both the single-GET `allow` (catalog.rego:47–50) and `category.rego`'s own `filter` (category.rego:214–260) enforce. For a **tag-gated catalog role** the list residual partial-evaluates to unconditional `ALLOW_ALL`, so `GET /catalogs` returns full DTOs for catalogs that `GET /catalogs/{id}` correctly 403s. The cross-angle pass shows the triggering role is not a rare coincidence but is **deliberately self-authorable** by a team administrator, because `RoleDefinitionService.validateContract` never inspects `required_tags`. The leak is bounded to the caller's already-governed set (no cross-tenant escape, read-only metadata), which is why it is High rather than Critical — but it is a genuine list-vs-GET control divergence and **must be fixed before 1.0.**

---

## Findings table

| # | Severity | Angle | Title | File:line | Probe |
|---|----------|-------|-------|-----------|-------|
| 1 | **High** | Rego corpus / control-plane | catalog `filter` omits the tag conjunct `allow` enforces — tag-gated roles leak tag-mismatched catalogs into `GET /catalogs` | `infra/opa/policies/catalog.rego:155` | **CONFIRMED** (`opa eval --partial`) |
| 2 | Low *(was Medium)* | Demo-UI tokens | Demo SPA persists access + refresh tokens (and PKCE verifier/state/nonce) in `localStorage` | `example-demo-ui/src/auth.ts:22` | n/a (code-confirmed) |
| 3 | Low | Dependencies | Spring Boot 3.4.3 / Spring Framework 6.2.3 pin trails 2025 patch lines | `gradle/libs.versions.toml:3` | n/a (7.4 seed) |
| 4 | Low | Dependencies | Embedded Tomcat 10.1.36 trails mid-2025 DoS/rewrite advisories (CVE-2025-31650 / -31651) | `gradle/libs.versions.toml:3` | n/a (7.4 seed) |
| 5 | Info *(was Low)* | Dependencies / supply-chain | Published starter leaks full Tomcat + Spring MVC + Spring Security Web (+ Resilience4j) onto adopter classpath | `opa-abac-spring-security/build.gradle.kts:17` | n/a (runtimeClasspath-confirmed) |

---

## Per-finding detail

### Finding 1 — High — catalog `filter` drops the tag conjunct; tag-gated roles leak tag-mismatched catalogs into `GET /catalogs`

- **Angle:** Rego policy corpus × control-plane role authoring (composite — see Cross-angle).
- **File:** `infra/opa/policies/catalog.rego:152–165` (the omission); template fix in `infra/opa/policies/category.rego:236–260`.
- **Probe result:** **CONFIRMED** end-to-end against code, ADRs, tests, and live OPA 1.10.1.

**Summary.** The catalog list-residual rule `filter` (catalog.rego:155–165) checks only the role-definition list grant plus `not filter_list_denied`, with **no `tags_satisfied` conjunct** — unlike the single-GET `allow` (catalog.rego:47–50), which requires `tags_satisfied`, and unlike `category.rego`'s `filter` (category.rego:214–220), which **does** include `filter_tags_satisfied`. So for a tag-gated role governing a catalog, the list residual partial-evaluates to unconditional `ALLOW_ALL` (`filter if "catalog" = input.resource.type` — true once the type is known), while the same role's single-GET returns 403 on a catalog whose tags don't satisfy the requirement. The list therefore returns full Catalog DTOs (name, description) for catalogs the caller may not view.

**Concrete attack.** A team administrator creates a custom role with `permissions {"catalog":["READ"]}`, `required_tags {"region":["emea"]}`, `match_mode ANY_OF`, `roleLevel 10`. `RoleDefinitionService.create` accepts `required_tags` on any-typed permissions (only CONTROL / team-management tokens are rejected). The team's `target_type` is `catalog`, governing catalog **C** whose tags are `{"region":"apac"}`. A member assigned this role:

- `GET /catalogs/{C}` → `catalog.allow` evaluates `tags_satisfied=false` → **403** (correct).
- `GET /catalogs` → `CatalogListAuthorizer.readable` resolves `governedIds=[C]`, compiles `catalog.filter` → residual is `ALLOW_ALL` (`fullySupported()==true`), so `AbacQueryService.findAuthorized` skips the `!residual.fullySupported()` tag-aware batch recheck (AbacQueryService.java:190) and runs the pure-SQL path `scopeOnly(scope).and(ALLOW_ALL).and(notDenied())` (authorizedSpec, AbacQueryService.java:283–289). `notDenied()` filters only the `abac_deny` tag (line 312–320), **not region** — so **C is returned with its full row.**

**Verdict reasoning.** CONFIRMED. Mechanism reproduced live: for `{permissions:{catalog:[READ]}, required_tags:{region:[emea]}, match_mode:ANY_OF}`, `catalog.filter` reduces to `filter if "catalog" = input.resource.type` — an unconditional `ALLOW_ALL` with **no** tag predicate. The **identical** role on `category.filter` yields the tag predicate `input.resource.attributes.region = "emea"` (category.rego:214–260 has `filter_tags_satisfied`), and `catalog.allow` on a region=apac catalog returns `false` (catalog.rego:47–50 requires `tags_satisfied`). The list↔single-GET divergence is real.

Java path confirmed: an `ALLOW_ALL` `PartialResult` has `fullySupported==true` (`PartialResult.allowAll()`), so `AbacQueryService.findAuthorized` skips the batch recheck and runs the pure-SQL path; `notDenied()` filters only `abac_deny`, so a governed catalog tagged region=apac survives `id IN (governedIds) ∧ ALLOW_ALL ∧ notDenied` and is returned as a full `CatalogEntity` DTO via `CatalogController → CatalogListAuthorizer.readable` (the sole authority for `GET /catalogs`).

Reachability confirmed: `RoleDefinitionService.create` (RoleDefinitionService.java:60–86) accepts `required_tags` on catalog-typed permissions with no restriction; `EffectiveRoleService.resourceRole` (EffectiveRoleService.java:162–171) threads `required_tags`+`match_mode` into the resolved `RoleDefinition` returned by `/internal/effective-role`, which drives **both** the list residual and the single-GET `allow` via the same `HttpRoleDefinitionSupplier`. One path honors the tag requirement; the other drops it.

Intended-to-prevent confirmed: `catalog_test.rego:208` explicitly states "role's required_tags must gate catalog actions exactly as it gates category actions," and P2 (line 243) asserts the single-GET denies a region=apac catalog for the emea-gated role — yet the filter tests (R1–R3, lines 302–336) exercise only tag-free roles, leaving this gap uncovered. `category.rego:196/236` documents the invariant the catalog filter rule breaks: "list and single-GET agree on which rows are visible."

Refutation attempts failed. ADR 0018 §3 deliberately makes `catalog.filter` tag-free, but only on the premise that catalog visibility is a pure membership question and catalogs carry no access-relevant tags; it does not contemplate a valid tag-gated role (ADR 0009, self-service-creatable) governing a catalog — an incomplete premise, not a documented waiver. The governed-scope AND-gate does not save it: the member **is** in the governing team, and the tag requirement is precisely what should further exclude the mismatched catalog; it is dropped only on the list path.

**Probe evidence + repro.**

```sh
# In infra/opa. Region-gated role → catalog.filter residual:
opa eval --partial --format pretty \
  -d policies/catalog.rego \
  --unknowns 'input.resource.attributes' \
  --input tag_gated_catalog_role_input.json \
  'data.catalog.filter'
# => residual: `filter if "catalog" = input.resource.type`  (NO region predicate → ALLOW_ALL)

# Same role, category policy for contrast:
opa eval --partial --format pretty \
  -d policies/category.rego \
  --unknowns 'input.resource.attributes' \
  --input tag_gated_catalog_role_input.json \
  'data.category.filter'
# => residual carries `input.resource.attributes.region = "emea"`  (tag predicate present)

# Single-GET on a region=apac catalog for the same role:
opa eval -d policies/catalog.rego --input apac_catalog_input.json 'data.catalog.allow'
# => false  (403, correct)
```

Live end-to-end confirmation for the follow-up branch: seed a team governing an apac-tagged catalog, assign a member a role with `required_tags region=[emea]`; expect `GET /catalogs` to **not** list that catalog (it currently does) while `GET /catalogs/{id}` 403s.

**Suggested fix direction (not the fix).** Port `category.rego`'s inline `filter_key_satisfied` / `filter_tags_satisfied` (category.rego:236–260) into `catalog.rego`'s `filter` so the residual carries the tag predicate. The catalog policy already has the `resource_tag_values` / `key_satisfied` helpers used by `allow`; the PE-friendly filter variants need porting. Additionally consider validating `required_tags` in `RoleDefinitionService.validateContract` (it currently never inspects them at all — see Cross-angle) as a second, independent hardening seam.

---

### Finding 2 — Low *(entered as Medium)* — Demo SPA persists tokens in `localStorage`

- **Angle:** Demo-UI token handling.
- **File:** `example-demo-ui/src/auth.ts:22`.
- **Probe result:** n/a (code-confirmed; no deployed production origin to probe).

**Summary.** The `UserManager` is configured with `userStore: new WebStorageStateStore({ store: window.localStorage })`, so the full OIDC bundle — access_token, id_token, **and refresh_token** — is written to `localStorage`. Because `stateStore` is unset, oidc-client-ts v3 defaults it to `window.localStorage` too (oidc-client-ts.js:1073), so the transient PKCE `code_verifier`, `state`, and `nonce` also land there. `localStorage` is readable by any same-origin JS, so any XSS can exfiltrate a live bearer token and the refresh token. The in-code comment frames this as "demo convenience," but no caveat steers an adopter away from it in production, and `example-demo-ui` ships **no README**.

**Concrete attack.** Attacker lands any script execution on the SPA origin (stored/reflected XSS, malicious npm/transitive dep, compromised asset) → reads `oidc.user:<authority>:catalog-spa` → exfiltrates the access_token (immediate API access as the victim) and the refresh_token (re-mints access tokens for its refresh lifetime, surviving tab close).

**Verdict reasoning.** Every technical claim verifies. `auth.ts:22` deliberately overrides `userStore` to `localStorage` (the default would have been `sessionStorage`); `stateStore` unset → v3 defaults to `localStorage`; `localStorage` is same-origin-JS-readable. Nothing upstream mitigates (the gateway validates bearers but does nothing about client-side storage; no CSP, no BFF, no service-worker).

Two factors pull severity **down** to low: (1) scope is `openid profile email` with **no `offline_access`**, so Keycloak issues a session-bound refresh token (SSO-session lifetime), not a true offline credential — the "offline re-minting past the session" flavor is overstated. (2) Per CLAUDE.md the entire `example/` tree — including this SPA — is an **unpublished demo, not the deliverable**; only the `opa-abac-*` library modules ship. There is no deployed production system to attack; the harm is pedagogical (an adopter copying the scaffold inherits a bad SPA-credential pattern). That is a legitimate concern for a teaching/portfolio artifact, so the finding **survives** — but as a docs/hardening issue on a demo, not an exploitable vuln in a shipped system. **Adjusted medium → low.**

**Suggested fix direction (not the fix).** Model the safer default: keep tokens in memory (or a service-worker / BFF that never exposes the refresh token to JS); at minimum move the token store to `sessionStorage`. Add a README to `example-demo-ui` with an explicit "demo-only, do not copy verbatim to production" caveat next to the storage choice.

---

### Finding 3 — Low — Spring Boot 3.4.3 / Spring Framework 6.2.3 trails 2025 patch lines

- **Angle:** Dependencies (CVE currency; 7.4 seed).
- **File:** `gradle/libs.versions.toml:3`.
- **Probe result:** n/a (version-currency flag, no reachable sink today).

**Summary.** `springBoot="3.4.3"` (Feb 2025) drives the whole Spring BOM, resolving Spring Framework 6.2.3 (spring-core/web/webmvc/expression) for both example services and the published starter's transitive stack. Several 2025 Spring Framework advisories were fixed later on the 3.4.x/6.2.x line — e.g. **CVE-2025-22233** (DataBinder `disallowedFields` bypass, fixed 6.2.7) and **CVE-2025-41234** (RFD / Content-Disposition encoding, fixed 6.2.8). 3.4.3 predates these. This is a version-currency flag for the 7.4 CVE audit, **not** an authorization fail-open in the ABAC logic.

**Concrete attack.** A network attacker hitting the example services (full Spring MVC) could probe framework-level advisories 6.2.3 predates (RFD download-filename injection, DataBinder property-binding bypass) rather than the ABAC gate itself → exploit depends on which controllers/binders are exposed; confirm against the fixed-version list during 7.4.

**Verdict reasoning.** Facts verify: `spring-boot-dependencies-3.4.3.pom` pins `spring-framework.version=6.2.3`; the version reaches consumers of the **published** starter (opa-abac-spring-security declares `spring-boot-starter-web` as `api`, re-exported transitively). The cited CVEs are real 2025 6.2.x-line fixes that 6.2.3 predates. The attack correctly deflates itself: grep across all example + library source found **zero** DataBinder/`@InitBinder`/`setDisallowedFields` usage and **zero** file-download/Content-Disposition/`InputStreamResource` handlers, so neither named CVE has a reachable sink today. The finding does not claim an authorization fail-open — it is self-labeled a currency flag, low severity, with the attack openly conditioned on 7.4 confirmation. Stands at low/informational.

**Suggested fix direction (not the fix).** Bump the Spring Boot 3.4.x pin to the latest patch on the line and re-audit the resolved Framework version against the 2025 advisory list during 7.4.

---

### Finding 4 — Low — Embedded Tomcat 10.1.36 trails mid-2025 DoS/rewrite advisories

- **Angle:** Dependencies (CVE currency; 7.4 seed).
- **File:** `gradle/libs.versions.toml:3` (Tomcat unpinned; resolved via BOM).
- **Probe result:** n/a (availability-only, demo rig).

**Summary.** The BOM resolves `tomcat-embed-core/el/websocket` 10.1.36 for both example services (and transitively — see Finding 5). 10.1.36 **is** patched for the CVE-2025-24813 partial-PUT RCE (fixed 10.1.35), but later 10.1.x advisories — **CVE-2025-31650** (memory-exhaustion DoS via malformed HTTP priority headers) and **CVE-2025-31651** (rewrite-rule bypass) — were fixed in **10.1.40**. 10.1.36 predates 10.1.40. Read-and-flag for the 7.4 CVE audit.

**Concrete attack.** An unauthenticated attacker sends crafted/malformed HTTP requests to an exposed example service → connector memory exhaustion / request-processing DoS (CVE-2025-31650), degrading availability of the demo rig. Confirm exact fixed version and exposure surface in 7.4.

**Verdict reasoning.** The Boot 3.4.3 BOM manages `tomcat.version=10.1.36`; Tomcat is unpinned anywhere in the repo, so 10.1.36 resolves. Per the Apache Tomcat security-10 page, 10.1.36 is affected by both CVEs, fixed in 10.1.40; the version arithmetic (including the correct note that 10.1.36 already contains the 10.1.35 fix) is accurate. Refutations partially land but don't kill it: (1) **CVE-2025-31651** requires a Tomcat `RewriteValve` — none is configured (every "rewrite" hit is domain ltree path logic), so that CVE is unreachable; the finding treats it as read-and-flag, not an asserted attack. (2) The asserted attack is only **availability degradation of the demo rig** via CVE-2025-31650, a generic connector-level DoS reachable on any exposed embedded Tomcat. (3) The service is a demo gated behind APISIX in the intended topology (gateway commented out in compose; only Postgres runs), so no confidentiality/integrity/authz guarantee is breached. (4) The adopter-inheritance angle is overstated here: `spring-boot-starter-web` is `implementation`-scoped in opa-abac-spring-security, and adopters bring their own Boot BOM (hence their own Tomcat). Net: factually correct, honestly self-scoped, actionable via a single Boot 3.4.x point bump to pull Tomcat ≥10.1.40. Stays low.

**Suggested fix direction (not the fix).** Bump Boot to a 3.4.x patch that pulls Tomcat ≥10.1.40; re-confirm exposure surface in 7.4.

---

### Finding 5 — Info *(entered as Low)* — Published starter leaks the full servlet stack onto adopter classpath

- **Angle:** Dependencies / supply-chain surface.
- **File:** `opa-abac-spring-security/build.gradle.kts:17`.
- **Probe result:** n/a (runtimeClasspath-confirmed; no live CVE riding along).

**Summary.** `opa-abac-spring-security` declares `spring-boot-starter-web` and `spring-boot-starter-security` as `implementation` (lines 17–18) and Resilience4j as `api` (lines 24–25); the published `opa-abac-spring-boot-starter` then `api`-depends on that module. Resolving the starter's runtimeClasspath confirms an adopter transitively inherits `tomcat-embed-core` 10.1.36, `spring-webmvc` 6.2.3, `spring-security-web` 6.4.3, `spring-boot-starter-tomcat`, and resilience4j 2.2.0 whether or not they want a servlet web stack. The starter's own build comment (opa-abac-spring-boot-starter/build.gradle.kts:26–40) already documents this as a known caveat and a tracked follow-up. Consequence for the CVE audit: every CVE in those transitive pins rides along to library adopters, not just the demo apps.

**Concrete attack.** An adopter who pulls `opa-abac-spring-boot-starter` into a service that does **not** otherwise use servlet Tomcat still ships embedded-Tomcat 10.1.36 / Spring Security Web 6.4.3 (and any of their advisories) on their runtime classpath and in their SBOM, widening attack surface beyond what the documented `@ConditionalOnClass` back-off implies. Note: `opa-abac-core` itself is verified Spring-free (runtimeClasspath = jackson-databind 2.18.2 + slf4j-api 2.0.16 only), so the leak is scoped to the starter aggregate, not core.

**Verdict reasoning.** Every factual claim is confirmed by direct Gradle resolution (JDK 21): the starter's runtimeClasspath contains exactly the pins named; `opa-abac-core`'s is Spring-free. The `@ConditionalOnClass` auto-config back-off governs bean **wiring**, not classpath **presence**, so it does not shrink the transitive SBOM — the finding states this correctly, and the transitive-surface claim is real and unrefuted. **However**, no attacker gains any capability the design intends to prevent: no live exploitable CVE is identified riding along (the pins are current 2025 patch-line versions), so this is a theoretical-surface / supply-chain-hygiene observation. It is a documented, self-disclosed, accepted design trade-off (build comment 26–40 + tracked follow-up in `docs/code-review/FULL-REPO-REVIEW-2026-06-10.md:139–142`), with documented adopter escape hatches (depend on core / spring-data directly, exclude modules, or `opa.abac.resilience.enabled=false`). Not a control-plane, tenant-isolation, or fail-closed defect. Accurate and stands, but at **info** level, not a vulnerability. **Adjusted low → info.**

**Suggested fix direction (not the fix).** Track the already-documented "starter dependency-scope restructure" follow-up: split the servlet-web transitive surface behind an optional/feature module or narrow scopes so a non-servlet adopter isn't forced to inherit embedded Tomcat.

---

## Cross-angle pass

I traced every survivor to its actual code seam and looked for compositions the per-angle passes could not see. One genuinely new composite emerged; the rest of the set is internally consistent, with no contradictions to reconcile. The two probe-driven downgrades are correct and undisturbed.

### NEW COMPOSITE (elevate framing, keep High): self-authored `required_tags` × catalog `filter` tag-drop = an *attacker-engineerable* list/GET divergence

Finding 1 (catalog `filter`) and the control-plane role-authoring surface were reviewed by different angles. Composed, they change the finding's severity **narrative** (not its blast radius).

- The filter-angle verdict called the trigger config "unusual but entirely legitimate." The control-plane angle supplies the missing half: **the trigger is deliberately constructable by a low-privileged, in-scope actor.** `RoleDefinitionService.validateContract` (RoleDefinitionService.java:132–159) validates **only** `roleLevel`, permission categories, ceilings, and denial-subtraction. `requiredTags` and `matchMode` pass straight through to the entity **unchecked** (`create` lines 80–84; `update` lines 96–101). No validation that the tag keys/values are meaningful; no restriction on attaching a tag requirement to a `catalog`-typed READ role. So a team administrator (level-30 GRANT, self-service per ADR 0009) can **intentionally** mint `{permissions:{catalog:[READ]}, required_tags:{region:[emea]}, match_mode:ANY_OF}`.
- `EffectiveRoleService.resourceRole` (EffectiveRoleService.java:162–171) threads that `required_tags`+`match_mode` **identically into both decision paths** via the same `HttpRoleDefinitionSupplier`. Single-GET honors it; list residual drops it. Result: the admin can hand a member a role that **provably denies `GET /catalogs/{C}` yet lists C's full DTO via `GET /catalogs`** — a feature of the config they author, not a rare accident of pre-existing tags.

**Bounded, not cross-tenant.** The amplification does **not** escape the governed set: `CatalogListAuthorizer.readable` passes `subtreeSpec = null` (catalogs are roots), so `authorizedSpec` reduces to `scopeOnly(scope).and(tagResidual).and(notDenied())` with no OR-widening branch (AbacQueryService.java:283–289). The leak is strictly `id IN (governedIds) ∧ ALLOW_ALL ∧ notDenied` — metadata of catalogs the member already governs by membership, never another tenant's. So the composite **raises confidence/intentionality, not blast radius**: it stays **High**, but as a self-service-reachable intra-set control divergence rather than a "wait for a coincidental tag mismatch" edge. Fix is unchanged (port `filter_tags_satisfied`/`filter_key_satisfied`), plus the second seam: `validateContract` never inspects `required_tags`.

### Contradiction check — the `bulk`/single-GET vs `filter` inconsistency is real and internal

`catalog.rego`'s `bulk`/action-enrichment path maps `allow` (tag-aware) per item, while `filter` is tag-blind. This is the same divergence from a different entrypoint: batch/enrichment decisions correctly deny the tag-mismatched row while the list residual admits it. Not a separate bug — corroborating evidence that `filter` is the sole tag-blind outlier in the catalog policy, which **strengthens** the High.

### Downgrades confirmed (no elevation from composition)

- **Demo SPA `localStorage` tokens → correctly low.** Could a stolen SPA token drive the malicious-role authoring composite? It could — but only if the victim already holds team-admin GRANT, and the SPA is unpublished demo scaffolding with no production origin to XSS. The composition manufactures no privilege the victim lacks. The low (docs/pattern) rating stands.
- **The three dependency-currency findings → low/info.** They do not compose with the authorization findings: no reachable DataBinder/RewriteValve/download sink exists (grep-confirmed), and the starter-leak is `implementation`-scoped so adopters bring their own BOM. No chain like "Tomcat DoS → fail-open list" exists — every fail-closed edge is availability-preserving-closed (`AbacQueryService` compile-error → `List.of()`/`Page.empty`; `CatalogListAuthorizer` outage → empty page), so a framework DoS degrades availability only, never opens the gate.

### Net

- One finding to **elevate in framing** (keep High): catalog-filter tag-drop composed with unchecked `required_tags` authoring = a self-service-reachable list-vs-GET control divergence.
- **No findings refuted** by the cross-angle view; both probe downgrades (SPA→low, starter-leak→info) are correct and unaffected by composition.
- **No cross-tenant escape and no privilege creation** from any composition; every fail-closed edge holds closed under the dependency-DoS angle.

**Key files:** `infra/opa/policies/catalog.rego:152–165` (the omission) · `infra/opa/policies/category.rego:236–260` (the fix template) · `example-user-management-service/.../RoleDefinitionService.java:80–84,132–159` (unchecked `required_tags`) · `example-user-management-service/.../EffectiveRoleService.java:162–171` (threads tags into both paths) · `example-catalog-management-service/.../CatalogListAuthorizer.java` (`subtreeSpec = null` bounds the leak) · `opa-abac-spring-data/.../AbacQueryService.java:283–289` (pure-SQL composition that skips the tag recheck when `fullySupported()`).

---

## Refuted at runtime

None. No survivor was refuted by its probe or by the cross-angle pass. (Two findings were **downgraded** — see Findings 2 and 5 — but neither was refuted.)

---

## Deferred to 7.4

Explicitly **out of scope** for this baseline and carried to the 7.4 delta review + publish checklist:

- **Git-history secret scan** — full-history scan (not just working tree) for committed credentials/tokens. This pass verified no secrets in the current tree only.
- **Full CVE audit** — Findings 3–5 are currency **seeds**, not a completed audit. 7.4 should run a resolved-dependency CVE scan (SBOM + advisory cross-check) after the version bumps land, confirming exact affected/fixed versions and reachable sinks.
- **Signing / supply-chain** — artifact signing, provenance/SLSA attestation, and reproducible-build posture for the published starter.
- **Zero-config fail-safety** — the out-of-the-box (no explicit config) behavior of the published starter: does a bare adopter get fail-closed defaults, and does `opa.abac.*` disabled degrade safely?

---

## Follow-up branch — triage list (ordered by severity)

Fixes land on a follow-up branch off `b7ad6a7`, **not** here.

1. **[High] Fix `catalog.rego` `filter` tag-drop.** Port `filter_tags_satisfied` / `filter_key_satisfied` from `category.rego:236–260` so the catalog list residual carries the tag predicate. Add filter tests covering a **tag-gated** catalog role (R1–R3 currently only cover tag-free roles); assert list ↔ single-GET agreement on a region-mismatched catalog. **Secondary seam:** validate `required_tags` / `match_mode` in `RoleDefinitionService.validateContract` (currently passed through unchecked at RoleDefinitionService.java:80–84 / 96–101).
2. **[Low] Demo SPA token storage** (`example-demo-ui/src/auth.ts:22`). Move to in-memory/service-worker/BFF or at minimum `sessionStorage`; add an `example-demo-ui` README with an explicit "demo-only, do not copy to production" caveat.
3. **[Low] Bump Spring Boot 3.4.x** to pull Framework ≥6.2.8 (`gradle/libs.versions.toml:3`); re-audit against 2025 Framework advisories at 7.4.
4. **[Low] Bump Boot to pull Tomcat ≥10.1.40** (same pin); addresses CVE-2025-31650 (and unreachable-but-flagged CVE-2025-31651).
5. **[Info] Starter dependency-scope restructure** (`opa-abac-spring-security/build.gradle.kts:17`). Track the already-documented follow-up to stop leaking the full servlet stack onto non-servlet adopters; no urgency (no live CVE riding along).
