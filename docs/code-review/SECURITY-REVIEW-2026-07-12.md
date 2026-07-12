---
tags:
  - type/review
  - status/active
  - area/security
---
# Security Review — 2026-07-12 (7.4 pre-publish delta)

- **Date** 2026-07-12 · **Commit** `5a81fad` (post-SB4-port `main`) · **Scope** 7.4 pre-publish **delta** since the 7.0.5 baseline ([[PHASE-7-BASELINE-SECURITY-REVIEW]]), 8 angles (Rego corpus, control-plane privesc, tenant isolation, fail-closed edges, gateway/deploy config, demo-UI tokens, secrets, deps) · **Method** multi-agent fan-out → adversarial verify → cross-angle pass → synthesis, then reviewer independent re-verification of the High via `opa eval`. **Report-only** — fixes land on a separate follow-up branch.

> **Live-probe caveat (read first).** The workflow ran **zero live rig probes** (`probed: 0`). F1 is
> nevertheless **CONFIRMED**: it is a static-policy defect proven by `opa eval` against the repo's own
> policy files (OPA 1.10.1), independently re-verified by the reviewer.
>
> **Correction (2026-07-12, post-run):** the run's claim that "the rig's OPA is loaded with an unrelated
> `paas/projects` bundle" was a **probe-targeting error, NOT a rig defect** — and is retracted. The host
> has **two OPA servers from two runtimes** colliding on ports: the **docker starter OPA** publishes to
> `localhost:28181` and correctly serves this repo's `catalog/category/product` bundle; a separate
> **podman** OPA from an unrelated project (`opa-standalone`) publishes to `localhost:8181` and serves
> the `paas/projects` corpus. The live probes queried `:8181` and hit the wrong server. The starter rig
> was healthy throughout. **The correct starter-OPA probe port is `:28181`** (docker is dedicated to this
> project; podman hosts the other stack). The F1 verdict is unaffected — it never depended on a live probe.

## Executive summary

| Severity | Count |
|----------|-------|
| Critical | 0 |
| High     | 1 |
| Medium   | 0 |
| Low      | 0 |
| Info     | 1 |

**The single most important thing:** `catalog.rego` is missing the `abac_deny` deny-override that both `category.rego` and `product.rego` carry. A catalog explicitly denied (operator DB flag) is correctly hidden from `GET /catalogs` (the SQL `notDenied` mirror runs for catalogs), but is still **returned, updatable, and deletable** via the single-resource gates — a list↔single-GET fail-open on the root type that defeats a legitimately-set operator kill-switch, including on DELETE. This is the exact invariant ADR-0010 §3 was written to close, and it was never ported to the root type. Fix before 1.0.

## Findings

| Severity | Angle | Title | File:line | Probe |
|----------|-------|-------|-----------|-------|
| High | Rego corpus / composition | `catalog.rego` missing `abac_deny` deny-override → single-resource gates fail open on a denied catalog | `infra/opa/policies/catalog.rego:45-77` (allow at 57-77) | CONFIRMED (offline OPA 1.10.1) |
| Info | Dependencies | Optional `keycloak-directory` module drags EOL/vulnerable transitives (commons-io 2.11.0, HttpComponents 4.x) via RESTEasy | `opa-abac-keycloak-directory/build.gradle.kts:21` | n-a (posture flag, unpublished path) |

---

## Finding 1 — `catalog.rego` missing `abac_deny` deny-override (HIGH, CONFIRMED)

**File:** `infra/opa/policies/catalog.rego:45-77` (`allow` at 57-77) · **Angle:** Rego corpus / list↔single-GET composition · **Probe:** CONFIRMED (offline OPA 1.10.1)

### Summary
`category.rego` and `product.rego` both carry `denied if input.resource.attributes.abac_deny == true` and AND `not denied` into `allow`. `catalog.rego` has **no such rule anywhere** — its `allow` is `granted`-only, with no `not denied` conjunct and no `denied` rule in the package (`grep -c abac_deny`: catalog 0, category 1, product 2).

The list side *does* enforce the deny for catalogs: `CatalogListAuthorizer.readable` → `AbacQueryService.findAuthorized` → `authorizedSpec` AND-s `notDenied()` (`abac_deny IS DISTINCT FROM true`) unconditionally, including for catalogs (`opa-abac-spring-data/.../AbacQueryService.java:284-290,313-325`). So a catalog tagged `abac_deny=true` is **excluded** from `GET /catalogs`, while the single-GET `catalog:view` / `catalog:update` / `catalog:delete` gates (which run `catalog.rego` `allow` via `@OpaPreAuthorize`, with the row's real tags loaded by `CatalogResourceResolver`) return `allow=true`. The two paths disagree on what "denied" means — a direct violation of ADR-0010 §3's stated invariant ("Single-GET and list agree on what 'denied' means"). The `notDenied` SQL mirror was added; the Rego side of the mirror was never ported to the root type.

Coverage gap corroborates: `catalog_test.rego` has **zero** `abac_deny` cells; `category_test.rego` has multiple; `product_test.rego` has one. Nothing asserts the override on the root type because it does not exist.

### Concrete attack
An operator sets `abac_deny=true` on catalog C (via the operational DB path — see attacker-model note) intending to block it. C correctly vanishes from `GET /catalogs`. But a member with a READ (or WRITE) role governing C can still `GET /catalogs/{C}`, and **update and delete** it, because `catalog.rego` `allow` ignores the deny tag. The intended kill-switch on the governing root silently does nothing on every single-resource path — the dangerous direction being that DELETE fails open.

### Verdict reasoning
CONFIRMED — real, reproduced, and unfixed against shipped code, ADRs, tests, and a live OPA 1.10.1 eval.

Independently confirmed:
1. `catalog.rego` has no `abac_deny` deny-override; `allow` (57-77) is `granted`-only. `category.rego` (line 36 `granted; not denied`, line 121 `denied if abac_deny==true`) and `product.rego` (45-48, 127-129) both carry it.
2. Offline reproduction (OPA 1.10.1, against `infra/opa/policies`): `data.catalog.allow` for `{catalog:view, attributes.abac_deny=true, role_definition.permissions.catalog=[READ]}` → **true**; identical-shape `data.category.allow` → **false**; `{catalog:delete, abac_deny=true, catalog:[READ,WRITE]}` → **true**. Full suite `opa test .` = 228/228 (reasoning was against green policies).
3. List side honors the deny for catalogs (`notDenied` AND-ed unconditionally), so `GET /catalogs` excludes the row while single-GET/update/delete return it — genuine, asymmetric, fail-**open** divergence for the root type.
4. Violates ADR-0010 §3's explicit invariant that single-GET and list agree on "denied."
5. Coverage gap: `catalog_test.rego` has zero `abac_deny` cells.

**Attacker-model correction (narrows framing, not severity):** `abac_deny` is **not** client-writable. `TagAssignmentService.validateAndBuild` is a strict dictionary allowlist — any key with no applicable `TagDefinition` throws `IllegalTagAssignmentException` (→422), never stored; `abac_deny` is never seeded as a definition (no `abac_`/reserved-key guard exists in Java, and none is needed because the allowlist rejects unknown keys). Every real use sets it via DB `UPDATE ... jsonb_set` in the e2e scripts (`scripts/postman/run-hierarchy-matrix.sh:179`, `run-hierarchy-list-matrix.sh:168`), explicitly commented "operational control tag … directly in the DB." So the finding's original "any TAG-role holder can write it" clause is refuted — the real mechanism is an **operator DB flag**, the same mechanism the design uses for category/product deny. The vuln is the *defeat* of a legitimately-set operator kill-switch on every single-resource catalog path (including delete), not the ability to set one.

**Severity = High (bounded, not Critical):** reaching the single-GET requires the attacker to already govern catalog C by membership (a non-member resolves no role → denied at every non-create verb, ADR-0018) — no cross-tenant escape, no privesc. It requires an operator to have set the flag (not self-authorable). Against that, impact per incident is real and includes UPDATE and DELETE, the dangerous direction. Mirrors the sibling catalog `filter` tag-drop rated High; this one is less reachable (operator-flag-gated) but higher-impact per incident (defeats an explicit deny + permits delete) → nets to the same High.

### Probe evidence + repro
Offline, reproduced (from `infra/opa/policies`, OPA 1.10.1) — **re-verified independently by the
reviewer 2026-07-12**, with a probe-shape correction (see note):
```
cd infra/opa/policies
# input must be UNWRAPPED for `opa eval -i` (the bare input object, NOT {"input":{…}}),
# and action must carry the type prefix ("catalog:view", not "view") or `verb` is undefined.
# baseline — catalog:view, catalog:[READ], NO abac_deny        → true   (grant works)
# BUG      — catalog:view, catalog:[READ], abac_deny=true       → true   (deny ignored)
opa eval -d . -i catalog-view-deny.json   'data.catalog.allow'
# BUG      — catalog:delete, catalog:[READ,WRITE], abac_deny    → true   (destructive path)
opa eval -d . -i catalog-delete-deny.json 'data.catalog.allow'
# correct  — category:view, category:[READ], abac_deny=true     → false  (sibling denies)
opa eval -d . -i category-view-deny.json  'data.category.allow'
# correct  — product:view,  product:[READ],  abac_deny=true     → false  (sibling denies)
# gate     — catalog:view, abac_deny=true, NO role_definition   → false  (membership holds)
opa test .   # 228/228
```

> **Reviewer verification note (2026-07-12).** The reviewer independently reproduced this against the
> repo policies with `opa eval` (OPA 1.10.1) and confirmed every cell above:
> `catalog:view`/`catalog:delete` + `abac_deny=true` return **true**, the `category`/`product`
> siblings return **false**, and a role-less caller returns **false** (the B4 membership gate is
> upstream and holds). Two probe-methodology corrections were needed and are recorded so the
> follow-up branch's e2e cell is built right: (1) `opa eval -i` treats the *whole file* as `input`,
> so the input JSON must be the bare object, not `{"input":{…}}`; (2) `catalog.rego`'s `verb` splits
> on `":"`, so the `action` must be `"catalog:view"`, not `"view"`. An early mis-shaped probe returned
> a spurious `false` — flagged and corrected, not accepted.

**Live-rig probe during the run hit the WRONG OPA** (see the corrected caveat at the top): the run
queried `localhost:8181`, which is a **podman** OPA from an unrelated project serving a `paas/projects`
corpus — not this repo's docker starter OPA, which publishes to **`localhost:28181`** and serves the
`catalog/category/product` bundle correctly. So the live cell was never actually run against the starter;
F1 stands entirely on the `opa eval` confirmation above. The end-to-end cell, to run against **`:28181`**
(or through the gateway): set `abac_deny=true` on a catalog row via DB `UPDATE ... jsonb_set` (the client
tag path returns 422 — confirm it is rejected as non-allowlisted), then confirm `GET /catalogs` omits it
(200, absent from page). Pre-fix: `GET /catalogs/{id}` returns 200 and `DELETE /catalogs/{id}` succeeds
(the fail-open). Post-fix (PR #71, now on `main`): both single-GET and DELETE must return **403**.

### Suggested FIX DIRECTION (not the fix)
Port the `denied if input.resource.attributes.abac_deny == true` rule + a `not denied` conjunct on `allow` into `catalog.rego`, matching the sibling policies; add the missing `catalog_test.rego` `abac_deny` cells (currently zero) asserting single-GET/update/delete all deny a catalog tagged `abac_deny=true`, and list↔single-GET agreement.

---

## Finding 2 — `keycloak-directory` optional module: EOL/vulnerable transitives (INFO, n-a)

**File:** `opa-abac-keycloak-directory/build.gradle.kts:21` · **Angle:** Dependencies · **Probe:** n-a (dependency-posture flag; not reachable on the published path)

### Summary
`keycloak-admin-client:26.0.5` pulls JBoss RESTEasy 6.2.9.Final, which drags in `commons-io:2.11.0` (below 2.14.0, which fixed **CVE-2024-47554** — `XmlStreamReader` DoS, CVSS 4.3 Medium) and the EOL Apache HttpComponents 4.x line (`httpclient:4.5.14`, `httpcore:4.4.16`). Verified via `./gradlew :opa-abac-keycloak-directory:dependencies --configuration runtimeClasspath` and `dependencyInsight`: `commons-io` resolves to 2.11.0 (the Boot 4 BOM does not manage it, so no override).

This is a **posture flag to seed the deferred full CVE audit**, not a bypass/fail-open. Scope is tight: the module is `compileOnly` on the starter (`build.gradle.kts:47`) and `runtimeOnly` on the (non-published) `example-user-management-service` (`build.gradle.kts:28`). The published starter's `runtimeClasspath` has **0** matches for keycloak/resteasy/commons-io/httpclient/httpcore. No `maven-publish` plugin is wired anywhere → nothing ships to Maven Central today. `opa-abac-core` is Spring-free (`tools.jackson:3.1.4` + `slf4j:2.0.16` only). All repos are `mavenCentral()` only. Nothing else in the dep set is below a known CVE floor (Spring Boot 4.0.7 / Framework 7.0.8 / Security 7.0.6 / Tomcat-embed 11.0.22 / Jackson 3.1.4 / snakeyaml 2.5 / logback 1.5.34 are all current 2026-era releases past the well-known CVE lines).

### Concrete attack
Not directly exploitable on the published-starter path (module opt-in and unpublished). If an adopter enables `opa.abac.directory.keycloak.enabled` and feeds attacker-influenced XML through the RESTEasy multipart/`XmlStreamReader` path, `commons-io` 2.11.0's CVE-2024-47554 could cause a DoS. Note the vulnerable XML path is not exercised by `KeycloakUserDirectory` itself — it calls Keycloak over JSON only (`GET /admin/realms/{realm}/users?search=…`) to a trusted adopter-configured server, and `search()` is fail-closed (catches all exceptions → `List.of()`, never fail-open). Primary value is seeding the CVE audit with the concrete transitive to pin before publish.

### Verdict reasoning
Every factual and scope claim verified against live Gradle resolution and the CVE record. Transitives resolve exactly as claimed; CVE-2024-47554 affects commons-io 2.0–2.13.x (fixed 2.14.0), so 2.11.0 is in range. Scope tight and confirmed (compileOnly/runtimeOnly, 0 matches on the published runtimeClasspath, no publish tasks). The vulnerable path is not reachable via `KeycloakUserDirectory` (JSON-only, fail-closed). Correctly self-classified **Info**.

### Suggested FIX DIRECTION (not the fix)
When the deferred CVE audit runs: add a `commons-io` catalog pin (`>= 2.19.0`) as a constraint in the `keycloak-directory` module, and document/track the RESTEasy HttpComponents-4.x EOL exposure.

---

## Cross-angle pass

Both survivors confirmed against shipped code + OPA 1.10.1 live eval. **No new composite privesc/cross-tenant issue emerged.** The one plausible composite (deny-gap × hierarchy inheritance) is **refuted by design**.

**Finding 1 is a single-type gap, not a composite.** The catalog `abac_deny` gap is a same-type list↔single-GET asymmetry on the root type; it does not chain.

**Composite hypothesis TESTED and REFUTED — deny-gap × hierarchy inheritance.** A catalog-level `abac_deny` does not cascade to its subtree, by design:
- No `denied` clause in `category.rego`/`product.rego` consults `input.resource.ancestors` (grep: zero deny×ancestor matches). `denied` is leaf-own-tag only (`category.rego:120-121`).
- SQL mirror agrees: `AbacQueryService.java:304-306` documents the deny is AND-ed *outside* the widening OR "so a leaf deny overrides both the tag branch and the subtree branch" — deny is a per-row property of the reached row, never a subtree cascade.

So `abac_deny` is consistently **leaf-scoped** across Rego and SQL. The catalog gap is a same-type asymmetry, not an inheritance escape.

**No privesc / cross-tenant composition.** The membership gate is upstream and independent: reaching `catalog.rego` `allow` on a non-create verb requires a resolved `role_definition` (team membership) — a non-member resolves no role → denied (ADR-0018; confirmed live: non-member → false). The narrow surviving `catalog:create` realm fallback (`catalog.rego:79-90`) is verb-gated to `create` only, lives solely on `allow` (never `filter`), and `create` carries no resource and no `abac_deny`, so it does not compose with the deny-gap. The gap is fully bounded to a member already governing catalog C — exactly why it is High, not Critical.

**Finding 2 does not interact.** Different subsystem, opt-in and unpublished, no shared reachability with the deny-gap or any control-plane grant.

## Refuted at runtime (not open issues)

- **"Any TAG-role holder can set `abac_deny` on a catalog via the client tag path."** REFUTED. `TagAssignmentService.validateAndBuild` is a strict dictionary allowlist; unknown keys (including `abac_deny`, never seeded as a definition) → `IllegalTagAssignmentException` (422). `abac_deny` is an operator-only DB flag. (This narrows Finding 1's attacker model; the vuln — defeat of a legitimately-set flag — still stands.)
- **"The catalog deny-gap cascades through hierarchy inheritance into a subtree-wide leak."** REFUTED. `abac_deny` is leaf-scoped in both Rego and SQL (no deny×ancestor consultation; deny AND-ed outside the widening OR). No cascade.
- **"The deny-gap composes with tenant isolation into a cross-tenant leak or privesc."** REFUTED. Membership gate is upstream and holds (non-member → false live); `catalog:create` realm fallback is verb-gated to create, never on `filter`, carries no resource/`abac_deny`. Gap bounded to a governing member.
- **"The keycloak-directory `XmlStreamReader` CVE is reachable in the running system."** REFUTED. `KeycloakUserDirectory` is JSON-only and fail-closed; the vulnerable XML/multipart path is never exercised.

## Deferred (for the publish / delta pass)

Not covered in this delta pass; run before 1.0 publish:
- Git-history secret scan.
- Full CVE audit (this pass flagged one transitive to pin; a full sweep is deferred).
- Signing / supply-chain (once `maven-publish` is wired — none today).
- Zero-config fail-safety (behavior of a bare adopter with defaults).
- **Live-rig re-probe of F1** against the **docker starter OPA on `localhost:28181`** (NOT `:8181`,
  which is a podman OPA from an unrelated project — the run's original mis-target, now corrected above;
  no deploy-hygiene fix is needed, just the right port). The e2e cell is under F1's "Live-rig probe"
  paragraph. The static defect is already CONFIRMED by `opa eval`; the live re-probe is confirmatory,
  not gating, and should now show single-GET/DELETE → 403 with the PR #71 fix on `main`.

## Follow-up branch — triage (severity-ordered)

1. **[HIGH] Port the `abac_deny` deny-override into `catalog.rego`** — add `denied if input.resource.attributes.abac_deny == true` + a `not denied` conjunct on `allow`; add `catalog_test.rego` `abac_deny` cells (single-GET/update/delete deny, list↔single-GET agreement). Restores the ADR-0010 §3 invariant on the root type.
2. **[INFO] Pin `commons-io >= 2.19.0`** as a constraint in the `keycloak-directory` module; document the RESTEasy HttpComponents-4.x EOL exposure. Fold into the deferred CVE audit before publish.

---
*Honesty bar: a `needsProbe` finding with no probe result would be UNVERIFIED, not an open Critical — none here. Report-only; no fixes applied. This report gates a public 1.0.*
