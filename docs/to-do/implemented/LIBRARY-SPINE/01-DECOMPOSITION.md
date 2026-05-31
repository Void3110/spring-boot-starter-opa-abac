---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/spring-security
  - area/spring
---

# Library spine — decomposition

> The ordered work list. Each ticket is one focused commit. Design rationale is in [[00-DESIGN]];
> the cases this must satisfy are in [[10-QA-TEST-CASES]]. **This is the implementer's work list.**

Library packages under `dev.dmitriikonovalov.opaabac.{core,security,autoconfigure}`; example under
`dev.dmitriikonovalov.example.catalog`. Clean-room: original names only. Single-decision scope — **no**
batch / partial-eval / hierarchical work anywhere in this slice.

Critical path **T1 → T3 → T4 → T5 → T6 → T7**; **T2 is parallelizable with T1**. T1/T2 are
independently landable (pure unit tests, no app).

---

## Ticket 1 — Core: `HttpOpaClient` + `RoleDefinition`/SPI + policy-path resolver

**Goal:** a zero-extra-dependency, fail-closed `OpaClient` implementation, plus the role-definition
backbone, all in the Spring-free `opa-abac-core`.

**Deliverables**
- `…core.RoleDefinition` — record `(String code, Map<String,Object> attributes,
  Map<String,List<String>> permissions)`; defensive copies in the compact constructor; null → empty.
- `…core.RoleDefinitionSupplier` — `@FunctionalInterface`:
  `Optional<RoleDefinition> lookup(String userId, String resourceType, String resourceId)`
  (`resourceId` may be null).
- `…core.NoOpRoleDefinitionSupplier implements RoleDefinitionSupplier` — always `Optional.empty()`.
- Widen `…core.AbacContext` with a **nullable** `RoleDefinition roleDefinition` component (keep the
  existing `subject`/`action`/`resource`/`environment`; update the compact ctor + any callers). It
  remains the single OPA input model.
- `…core.PolicyPathResolver` — SPI: `String resolve(AbacContext context)` (path, no leading/trailing
  slash).
- `…core.PerTypePolicyPathResolver implements PolicyPathResolver` — returns
  `policyPrefix + "/" + resourceType` (trims empty segments; blank prefix ⇒ just `resourceType`).
- `…core.OpaClientConfig` — immutable carrier: `baseUrl`, `Duration timeout`, `String decisionField`
  (default `"allow"`).
- `…core.HttpOpaClient implements OpaClient` — JDK `java.net.http.HttpClient` (+ a shared
  `ObjectMapper`, the `PolicyPathResolver`, the `OpaClientConfig`); `allow(AbacContext)` resolves the
  path → POSTs `{"input": <context>}` → reads `result.<decisionField>` as boolean. **Fail-closed**:
  non-200 / `IOException` / timeout / connection-refused / malformed body / missing-or-non-boolean
  field ⇒ `false`; never throws for OPA/transport failures; logs WARN (path + status), never the token.
- Unit tests against an in-process `com.sun.net.httpserver.HttpServer` stub (no WireMock): allow / deny
  / 500 / connection-refused / timeout / malformed-body / missing-field → all deny; one test captures
  the request body and asserts the `{"input":{…}}` shape **including `role_definition`** + the resolved
  **per-type** path.

**Acceptance**
- `./gradlew :opa-abac-core:test` green.
- `opa-abac-core` still depends only on Jackson + SLF4J + the JDK (**no Spring**, no Feign).
- Every failure mode returns `false`, proven by the stub-server tests.

**What NOT to touch**
- Spring modules, the example app, infra. No `OpaClient` interface change beyond what serialization
  needs (it stays single-decision `allow`). No batch/partial methods. No retries.

---

## Ticket 2 — Security: `AbacSubjectExtractor` + `AbacFilter` + `AbacAuthentication`

**Goal:** turn the forwarded Bearer JWT into an authenticated principal carrying the `Subject`, in the
`SecurityContextHolder`, via a pluggable extractor and a `OncePerRequestFilter`. **Parallel with T1.**

**Deliverables** (all in `…opaabac.security`)
- `AbacSubjectExtractor` — SPI: `Optional<AbacContext.Subject> extract(HttpServletRequest request)`.
- `JwtClaimsSubjectExtractor implements AbacSubjectExtractor` — reads `Authorization: Bearer <jwt>`;
  base64url-decodes the **payload** segment only (**no signature verification**); parses with Jackson;
  **structural + `exp` checks** (3 segments, JSON object, not-expired — `exp` check toggleable, default
  on); maps `sub`→id, `realm_access.roles`→roles, `preferred_username` + configured claims→attributes.
  All claim names are configurable (constructor params / a small config object). Missing / malformed /
  expired ⇒ `Optional.empty()`.
- `AbacAuthentication extends AbstractAuthenticationToken` — wraps the `Subject`; `getName()` = id;
  `getAuthorities()` = `SimpleGrantedAuthority("ROLE_" + role)`; `isAuthenticated()` = true; typed
  `getSubject()`.
- `AbacFilter extends OncePerRequestFilter` — delegates to the extractor; on a present Subject sets the
  `AbacAuthentication` on the context; on empty leaves it anonymous; **always continues the chain;
  never throws** on a malformed token. Runs early (around the bearer-token filter slot).
- Unit tests: hand-crafted JWT strings (`base64url(header).base64url(payload).sig`, dummy sig) →
  Subject with id + realm roles + username; missing `sub`; missing roles → empty roles; flat `roles`
  claim; expired `exp` → empty; 2-segment / non-JSON payload → empty; no Authorization header → empty;
  configurable claim paths honored. `AbacFilter` with mock request/response/chain → context populated /
  anonymous pass-through / chain always continued.

**Acceptance**
- `./gradlew :opa-abac-spring-security:test` green.
- **No** signature-verification code; **no** `oauth2-resource-server` dependency added.
- Claim names configurable (no hardcoded Keycloak shape beyond defaults).

**What NOT to touch**
- core / starter / example / infra. No OPA call here. No method-security wiring (that's T3).

---

## Ticket 3 — Security: `@OpaPreAuthorize` + authorization manager + role-definition wiring

**Goal:** enforce decisions — a declarative method annotation (headline) and a request-level manager
(opt-in) — building the `AbacContext` from the Subject + an action + a type-level resource + the looked-up
role definition, calling OPA, denying with `AccessDeniedException`, failing closed.

**Deliverables** (all in `…opaabac.security`)
- `@OpaPreAuthorize` — `@Target(METHOD) @Retention(RUNTIME)`:
  - `String action()` (e.g. `"product:write"` — opaque to the library);
  - `String resourceType() default ""` (SpEL, e.g. `"'product'"`);
  - `String resourceId() default ""` (SpEL, e.g. `"#productId"`);
  - `String resource() default ""` (SpEL → an `AbacDataObject` for callers who hold the instance).
  Pre-invocation only (documented; return value not available).
- `OpaPreAuthorizeAuthorizationManager implements AuthorizationManager<MethodInvocation>` — Spring
  Security 6.4 `check(Supplier<Authentication>, MethodInvocation)` (add a code comment flagging the 7.0
  `authorize()` rename):
  - read the Subject from the current `AbacAuthentication` (unauthenticated ⇒ deny);
  - resolve the resource (type/id via SpEL, or an `AbacDataObject` via `resource()`);
  - **`roleDefinitionSupplier.lookup(subjectId, resourceType, resourceId)`** → `RoleDefinition`;
  - build `AbacContext(subject, action, resource, roleDefinition, environment)`;
  - `opaClient.allow(ctx)` → `new AuthorizationDecision(allowed)`; **any exception ⇒ denied
    (fail-closed)**, logged.
- `OpaMethodSecurityConfiguration` — a `@Bean AuthorizationManagerBeforeMethodInterceptor` advisor
  bound to a pointcut matching `@OpaPreAuthorize`, ordered before/around `@PreAuthorize`'s.
- `OpaAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext>` — minimal
  opt-in request-level manager (action = lowercased HTTP method; resource type from a configured map);
  provided as a bean, the app wires it into its chain if it wants request-level rules.
- Unit tests: mocked `OpaClient` + `RoleDefinitionSupplier`; a stub `MethodInvocation` with a resource
  arg + an authenticated `AbacAuthentication`. Assert allow→granted, deny→denied, OPA-error→denied,
  unauthenticated→denied, unresolvable-resource→denied; an `ArgumentCaptor` asserts the `AbacContext`
  sent to OPA carries the expected action + resource type/id + `role_definition`. SpEL resolution tests
  (`resourceType="'catalog'"`, `resourceId="#id"`, `resource="#product"`). A `spring-security-test`
  method-security slice: an `@OpaPreAuthorize`-annotated bean method is allowed/denied per the mocked
  client.

**Acceptance**
- `./gradlew :opa-abac-spring-security:test` green.
- Deny throws `AccessDeniedException` (→ 403); fail-closed proven by the OPA-error test; the looked-up
  `RoleDefinition` reaches the OPA input (captured).

**What NOT to touch**
- core (frozen by T1); example; infra. No batch/partial; no hierarchical ancestor resolution.

---

## Ticket 4 — Starter: auto-configuration (conditional + overridable)

**Goal:** "add the dependency + a few properties" turns the whole spine on; every bean overridable.

**Deliverables** (in `…opaabac.autoconfigure`)
- Extend `OpaAbacProperties` (keep `enabled`, `baseUrl`, `policyPrefix`, `timeout`):
  - add `decisionField` (default `"allow"`);
  - add a `subject` group: `subjectIdClaim` (`sub`), `subjectRolesClaim` (`realm_access.roles`),
    `subjectUsernameClaim` (`preferred_username`), `subjectAttributesClaims` (list, empty),
    `validateExpiry` (true);
  - add `verifySignature` (false — reserved; documented as unimplemented in this slice);
  - regenerate `spring-configuration-metadata.json` (configuration-processor already a dep).
- Beans (each `@ConditionalOnMissingBean`; security ones additionally
  `@ConditionalOnClass({SecurityFilterChain.class, OncePerRequestFilter.class})`; whole config under
  `@ConditionalOnProperty opa.abac.enabled` matchIfMissing):
  - `PolicyPathResolver` (← `PerTypePolicyPathResolver(policyPrefix)`);
  - `OpaClient` (← `HttpOpaClient` from `OpaClientConfig` mapped from properties + the resolver + an
    `ObjectMapper`);
  - `RoleDefinitionSupplier` (← `NoOpRoleDefinitionSupplier` default — the app overrides);
  - `AbacSubjectExtractor` (← `JwtClaimsSubjectExtractor` from properties);
  - `AbacFilter`;
  - `OpaPreAuthorizeAuthorizationManager` + the method-security advisor (`OpaMethodSecurityConfiguration`);
  - `OpaAuthorizationManager` (opt-in; provided, app wires it).
- **Do NOT** register a `SecurityFilterChain` — that's the application's job.
- Keep `AutoConfiguration.imports` pointing at the entry config (add any split classes).
- `ApplicationContextRunner` tests: enabled + security on classpath → all beans present;
  `enabled=false` → none; a user `@Bean OpaClient` / `@Bean RoleDefinitionSupplier` → starter backs off
  (`@ConditionalOnMissingBean`); `FilteredClassLoader` removing spring-security → only the core
  client/resolver beans; property binding (`decisionField`, `subjectRolesClaim`, …).

**Acceptance**
- `./gradlew :opa-abac-spring-boot-starter:test` green.
- Every bean overridable; security beans absent without security/web; metadata regenerated with the new
  properties.

**What NOT to touch**
- Don't auto-create a `SecurityFilterChain`. Don't change core/security public APIs (only wire).

---

## Ticket 5 — Example: security chain, demo role defs, annotations, per-type rego, retire enricher

**Goal:** the catalog app actually enforces ABAC via the library; the demo Lua enricher is retired; a
real per-type rego replaces allow-all; the app stays green and `ddl-auto: validate` is unaffected.

**Deliverables**
- Build: `example-catalog-management-service/build.gradle.kts` — add
  `implementation(project(":opa-abac-spring-boot-starter"))` (brings security + the auto-config).
- `…example.catalog.config.SecurityConfig` — `@Configuration @EnableWebSecurity
  @EnableMethodSecurity`; a stateless `SecurityFilterChain` (CSRF off, `SessionCreationPolicy.STATELESS`),
  installs the injected `AbacFilter` (e.g. `.addFilterBefore(abacFilter, AuthorizationFilter.class)`),
  permits `/actuator/health`,`/swagger-ui/**`,`/v3/api-docs/**`, requires auth on `/api/v1/**`.
- `…example.catalog.config.DemoRoleDefinitionSupplier` — `@Bean RoleDefinitionSupplier` (overrides the
  starter's no-op): maps the subject's realm roles → a `RoleDefinition` (`catalog-viewer` → read perms
  on catalog/category/product; `catalog-editor` → adds write).
- Annotate `Catalog/Category/Product` controllers with `@OpaPreAuthorize`: reads →
  `action="<type>:read", resourceType="'<type>'"`; writes → `:write`; optional `resourceId="#<idArg>"`
  on by-id ops.
- `…example.catalog.config.AuditingConfig` — replace the fixed `DEMO_PRINCIPAL` `AuditorAware` with one
  that reads the current `AbacAuthentication` from `SecurityContextHolder` and returns the Subject id as
  `UUID` (guard parse failures → empty); `Optional.empty()` when unauthenticated. `DateTimeProvider`
  unchanged.
- Per-type rego (use `/rego-skill`, with `opa test` companions): `infra/opa/policies/catalog.rego`,
  `category.rego`, `product.rego` — each `package <type>`, `default allow := false`, `allow` when the
  action verb ∈ `input.role_definition.permissions[input.resource.type]`, falling back to subject roles
  when no role definition. Keep `gateway.rego` coarse (APISIX layer).
- App→OPA wiring: `deploy.sh` pod env `OPA_ABAC_ENABLED=true`, `OPA_ABAC_BASE_URL=http://opa:8181`,
  `OPA_ABAC_POLICY_PREFIX=` (resolver returns per-type paths). Document the local `:28181` override.
- **Retire the Lua enricher:** remove the `serverless-pre-function` wiring from
  `infra/apisix/init-routes.sh`; delete `infra/apisix/enricher-plugin.py`; explain in `infra/README.md`.
  APISIX keeps `openid-connect` (validates + forwards the bearer).
- **ITs:** make `CatalogCrudIT`/`ProductConcurrencyIT` pass under a **permissive test setup** — a test
  `application-*.yml`/properties with `opa.abac.enabled=false` and/or a test `SecurityFilterChain`
  permitAll + a stub `OpaClient(allow=true)` / mock `AbacAuthentication`. They test persistence/
  concurrency, not authz; keep them unchanged-green.

**Acceptance**
- `./gradlew build` green; `CatalogCrudIT`/`ProductConcurrencyIT` still pass; `ddl-auto: validate` boots
  clean (no schema change).
- `opa test infra/opa/policies/` green for the new per-type policies.
- With the rig up, a viewer token GETs but is 403 on writes; an editor token writes — proven manually
  here, automated in T7.

**What NOT to touch**
- No new DB columns/migrations; no OpenAPI spec change; no in-app signature verification; no
  batch/partial.

---

## Ticket 6 — Infra: realm users + roles for the allow/deny matrix

**Goal:** the realm can express *viewer-denied-on-write* (the current `demo` user holds **both** roles,
so it can't).

**Deliverables**
- `infra/keycloak/realm-export.json` — add a **viewer-only** user (`viewer`/`viewer`, realm role
  `catalog-viewer`) and an **editor** user (`editor`/`editor`, roles `catalog-editor` +
  `catalog-viewer`). Keep `demo` (both) for back-compat, or repoint the existing suite to `editor`.
  Verify `realm_access.roles` is in the access token (the `roles` default scope on the existing
  `catalog-gateway` client provides it).
- Note in `infra/README.md` which user demonstrates which row of the matrix.

**Acceptance**
- The rig boots with the updated realm; in-network tokens for `viewer` and `editor` mint and carry the
  expected `realm_access.roles`.

**What NOT to touch**
- App/library code; the OPA policies (T5 owns them). Keep this commit infra-only so the realm change is
  isolated from the security wiring.

---

## Ticket 7 — E2E (allow/deny matrix) + docs + roadmap/Mulch

**Goal:** the e2e suite exercises real decisions (viewer-read-allowed, viewer-write-denied,
editor-write-allowed); docs tell the spine story; roadmap + Mulch updated.

**Deliverables**
- Postman (`scripts/postman/`): `run-tests.sh` mints **both** tokens in-network and injects
  `viewer_access_token` + `editor_access_token`; new folders — **Viewer read allowed** (GET → 200),
  **Viewer write denied** (POST/PUT/DELETE → **403**), **Editor write allowed** (the existing
  create→get→update→delete chain under the editor token). Keep chained ids in **collection** scope
  (mx-ecc3ef). Update `local.postman_environment.example.json` with the new user vars.
- Docs:
  - `docs/guides/ABAC-AUTHORIZATION.md` (new) — the spine: `HttpOpaClient` (fail-closed,
    `{input}`→`result`), `AbacSubjectExtractor`/`AbacFilter` (Bearer→Subject, **signature-trust posture
    + tradeoff**), `RoleDefinition`/`RoleDefinitionSupplier` (demo→Phase-4 swap), `@OpaPreAuthorize`
    (action + resource resolution), the starter properties, and the adoption recipe (add dep +
    properties + a `SecurityFilterChain` that installs `AbacFilter`).
  - `docs/architecture/TWO-LAYER-AUTHORIZATION.md` (new) — gateway (coarse authn + coarse OPA) vs app
    (fine-grained ABAC), why the enricher was retired, the per-type rego documents, role-definition-driven
    decisions.
  - update `docs/guides/E2E-TESTING.md` (the authz-depth note → a real viewer/editor matrix) and
    `infra/README.md` (enricher retired; app does ABAC; per-type policies).
  - update `docs/to-do/planning/POC-ROADMAP/POC-ROADMAP.md` — mark this slice done; note batch/partial
    still Phase 5.
  - on ship, move `LIBRARY-SPINE/` → `docs/to-do/implemented/` with a "Shipped" banner (the index note).
- Mulch: `ml record opa-abac …` the durable insights (fail-closed JDK-HttpClient client;
  role-definition-driven decision + pluggable `RoleDefinitionSupplier` demo→HTTP swap; pluggable claim
  extractor; signature-trust posture; starter must not seize the `SecurityFilterChain`; the
  demo-user-holds-both-roles gotcha; per-type rego docs). `ml sync` (`.mulch`-only commit); `ml doctor`
  clean.

**Acceptance**
- With the rig up (`ENABLE_OIDC=1 ./deploy.sh up --pods 2`, OPA carrying the per-type policies),
  `cd scripts/postman && ./run-tests.sh` green: viewer reads 200, viewer writes 403, editor writes
  succeed (stable across reruns).
- `run-tests.sh` `bash -n`-clean; collection + env JSON valid; docs/roadmap/Mulch updated; `ml doctor`
  clean; **clean-room scan clean** (no proprietary names/paths/ids in the diff).

**What NOT to touch**
- Don't push or open a PR. Don't add a newman CI job (rig not in CI — note as a follow-up). No
  batch/partial.

---

## Cross-cutting acceptance (the whole slice)

- `./gradlew build` green: all library modules + the example app + OpenAPI codegen + ITs.
- `opa-abac-core` is still **Spring-free** (Jackson + SLF4J + JDK only).
- **Fail-closed** holds at both layers (client + manager), proven by tests.
- The starter does **not** register a `SecurityFilterChain`; every bean is `@ConditionalOnMissingBean`.
- `ddl-auto: validate` boots clean (no schema change this slice).
- e2e allow/deny matrix green through the gateway.
- Docs (the two new guides + roadmap), Mulch, and the `STATUS-0N.md` notes are updated; clean-room scan
  clean. **Nothing pushed.**
