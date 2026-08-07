---
tags:
  - status/done
  - type/project
  - area/abac
  - area/spring
---

# STATUS — T4: catalog-service: the `SupervisedScopeClient` HTTP edge, fail-closed and resilience-wrapped

**Status:** ✅ DONE

## What shipped

The catalog side's half of the org-relation seam — **additive only**, zero existing behaviour changed.

- **`config.SupervisedScopeClient`** — `GET <base>/internal/supervised-targets?subject=&resourceType=`,
  modelled directly on the shipped `HttpGovernedScopeResolver` in the same package: the same
  classification discipline (only a `200` carrying a valid JSON array of UUIDs yields ids; **every**
  other outcome is an empty list plus one WARN), the same timeout handling, the same interrupt-flag
  restoration, the same WARN hygiene (status or exception class only — never the subject, never the
  body). It returns `List<UUID>`, never `null`, and **never throws**.
  It is deliberately **not** a second `GovernedScopeResolver`: the membership resolver keeps its
  single-bean identity, and `supervised := S \ M` is composed beside it in T5, the only place that
  knows both sets.
- **`supervisedCallGuard`** in `CatalogResilienceConfig` — the same `opa.abac.resilience.resolve.*`
  budget (same service, same read-only GET shape) but a **separate breaker instance**, injected with
  `@Qualifier("supervisedCallGuard")` exactly as `HttpRoleDefinitionSupplier` uses `resolveCallGuard`
  and `TagDefinitionClient` uses `tagCallGuard` (B3, ADR 0017). **Not** `resolveCallGuard`: sharing it
  would let a supervised-targets outage trip the breaker every persona's `/internal/effective-role`
  resolution depends on — turning this slice's degrade-to-membership-only into an empty page for
  everyone, and making U30 and E8 unsatisfiable.
- **A breaker failure is recorded only on the thrown retryable path.** The exchange body throws
  `TransientSupervisedException` for the transient subset (5xx/429, timeout, connection-refused) so the
  guard retries it and the breaker counts it; **every permanent failure returns the empty list from the
  body** — a fail-closed decision, terminal, un-retried, and never a breaker failure (`mx-951d2f`; the
  guard records `onError` only for a *thrown* fault). An exhausted transient and an open breaker both
  land on the same empty list.
- **`catalog.user-service.supervised-base-url`** — a **dedicated** property, defaulting to the shared
  `catalog.user-service.base-url`, wired in `application.yml` with a
  `CATALOG_USER_SERVICE_SUPERVISED_BASE_URL` env override so T6's second E8 pass can repoint **only**
  this edge (B3's `ENABLE_RESILIENCE_STUB=1` swaps the whole user-service the rest of the matrix needs).
  The bean is `@ConditionalOnProperty(catalog.role-source=http)` — present only when the user-service
  edge is configured, absent otherwise, so the list simply has no second leg (T5).

## Tests

`./gradlew :example-catalog-management-service:test` green · `./gradlew build` green (all modules,
Testcontainers ITs against real Postgres) · `opa test infra/opa/policies/` **274/274**, with
`git diff --stat main -- 'infra/opa/**'` still exactly T3's four files — T4 touches no policy.

- **`SupervisedScopeClientTest`** (in-process `com.sun.net.httpserver.HttpServer` stub — never
  WireMock): **U17** (two uuids parsed, distinct, order-independent), **U18** (`[]` is the
  authoritative "supervises nothing"), **U19** (`500` / `404` / `401` → empty, no throw), **U20**
  (blank `200` body), **U21** ×4 (unparseable JSON; `[123,"not-a-uuid"]`; one bad element among good
  ones; a `null` element — each discards the **whole** list, never a partial one — plus a non-array
  body), **U22** (timeout → empty, and asserted to return on the *request* timeout rather than on the
  stub's stall, i.e. the thread is not wedged; plus connection-refused), **U23** (interrupted mid-call
  → empty **and** the interrupt flag restored), and the no-coordinates/no-call and request-shape cases.
- **`EdgeResilienceTest`** (the existing virtual-clock class) gained **U24** ×4: a transient 5xx
  retries then recovers; an **exhausted** transient still fails closed to empty (3 attempts = 2 retries
  + 1); a fail-closed empty result is **not** a breaker failure (10 alternating `404`/`[]` responses →
  10 attempts, breaker `CLOSED`, `getNumberOfFailedCalls() == 0`); and a thrown transient outage **does**
  trip this edge's own breaker, whose open state still fails closed to empty.
- **`SupervisedEdgeWiringIT`** — the wiring half (see the review's finding 1): boots the
  `catalog.role-source=http` context against real Postgres, points **only** the shared
  `catalog.user-service.base-url` at a stub, and asserts the supervised client reaches
  `/internal/supervised-targets` there (the default chain resolves) and that
  `supervisedCallGuard`'s breaker is **not** `resolveCallGuard`'s.

## Architecture review + refactor

Ran the ★ gate inline after unit-green, before the wiring IT. **Two substantive findings, both fixed;
no invented churn.**

1. **The dedicated property's default chain was wrong (real, and it would only ever have surfaced on
   the rig).** The first draft defaulted `supervised-base-url` to the *environment variable*
   (`${CATALOG_USER_SERVICE_SUPERVISED_BASE_URL:${CATALOG_USER_SERVICE_BASE_URL:http://localhost:8080}}`),
   not to the *property* the ticket names. With `CATALOG_USER_SERVICE_BASE_URL` unset — every test
   context, and any deployment configuring the shared URL by any other means — the supervised edge would
   have silently fallen back to `http://localhost:8080` while the rest of the user-service edge went
   somewhere else: a silent supervised-scope outage, i.e. a permanently membership-only page with no
   symptom but a WARN. **Fixed** to `${CATALOG_USER_SERVICE_SUPERVISED_BASE_URL:${catalog.user-service.base-url}}`,
   and pinned by `SupervisedEdgeWiringIT` — which is *why* that IT was written rather than trusting a
   placeholder to be right.
2. **Static analysis: 4 findings, all fixed rather than exempted.** `S1128` (a stale `CallGuard` import
   left by narrowing the test helper's return type to `Resilience4jCallGuard`, needed to reach
   `breaker()`), and `S2925` ×3. Two of the three sleeps were the **documented by-design class**
   (`mx-302e78`: a sleep inside a mock `HttpServer` handler, deliberately past the client's timeout) —
   but they were trivially replaceable with `CountDownLatch.await`, which is *also* strictly more
   deterministic, so they were removed rather than claiming the exemption. The third was **not** that
   class and was a genuine defect: a `Thread.sleep(200)` on the test thread to "let the exchange get
   underway" before interrupting — a race. It became a latch handshake the stub counts down on arrival.

**Static-analysis gate: `CLEAN — 0 open findings` on the changed files.** No FP marking was needed.

The rest of the checklist, with what it found:

- **Fail-closed.** Every classification branch returns the empty list, asserted one test per class:
  non-200 (4xx and exhausted 5xx), blank body, unparseable body, malformed/`null` element, non-array
  body, timeout, connection-refused, interrupt, open breaker, blank coordinates. **A malformed element
  discards the whole result** — a partial supervised set is indistinguishable from a correct smaller
  one, which is exactly why the design collapses rather than degrades it. This is failure **class 1**
  (org source errored → the subject degrades to their **own memberships**): the client contributes
  nothing and T5's membership leg is untouched.
- **Security — the widening that would matter here** is this edge returning ids the subject does not
  supervise. It cannot originate here: the client is a pure transport + parse with **no** id synthesis,
  no caching, no merge with any other source, and a single bad element voids the whole array rather
  than yielding a partial one. The complementary widening — the *reduction* `supervised := S \ M` being
  skipped — is structurally **not** this ticket's to make: the client returns the raw set and says so
  in its Javadoc, and T5 owns the difference. The one genuine trust dependency is **configuration**:
  whoever can set `supervised-base-url` chooses the oracle. That is identical to the shipped
  `base-url`'s posture (both `/internal/**`, network-isolated, no public route), and splitting the
  property does not widen it — it only makes the *supervised* oracle independently repointable, which
  is what E8 needs. It is **not** a new trust boundary, but it is a new knob, so it is documented as
  such.
- **Concurrency / idempotency.** The client is immutable (final fields, no mutable state); `HttpClient`
  is thread-safe and shared; the breaker is R4j's, thread-safe by contract. The call is a read-only GET,
  so the guard's retry can never double-execute anything (ADR 0017 §3), and it runs on the request
  thread outside any write transaction (§4). Two concurrent list requests share the bean and cannot see
  each other's results.
- **Wiring.** The bean's named consumer is **T5's `CatalogListAuthorizer`** — the next ticket, per the
  decomposition's strict T4 → T5 order — so this commit lands with no *production* call site by design.
  It is not an untested seam: `SupervisedEdgeWiringIT` is a live consumer through the real Spring
  context (the bean resolves, the property chain resolves, the endpoint is reached), and every failure
  class is exercised through the non-happy path.
- **Boundary / additivity.** No library module touched (`git diff --stat main -- 'opa-abac-*'` empty);
  `opa-abac-core` untouched and still Spring-free; no Rego (274/274, the policy diff is still exactly
  T3's four files); no decision-envelope change. Byte-for-byte unchanged: `HttpGovernedScopeResolver`,
  `HttpRoleDefinitionSupplier`, `TagDefinitionClient`, `CatalogListAuthorizer` (T5's), the existing
  `resolveCallGuard` / `tagCallGuard` beans, and the OpenAPI spec.
- **Module-layer separation.** Derivation stays in the user-service; this ticket only *fetches*. No set
  difference and no role reasoning here — both are T5's, on the catalog side.
- **Pattern reuse.** Classification and WARN hygiene from `HttpGovernedScopeResolver`; the guard
  wrapping (one retryable carrier thrown from an `exchangeAndParse` body, `result -> false`, the
  `@Autowired` production constructor beside a 3-arg test/demo one) from `TagDefinitionClient` /
  `HttpRoleDefinitionSupplier` verbatim — `mx-24ac37`'s shape, including its Spring gotcha (two public
  constructors on a `@Component` need `@Autowired` on the production one).
- **SOLID.** One class, one responsibility (fetch + classify); the guard is injected behind the
  backend-agnostic `CallGuard` interface (DIP), so the future native-resilience swap is a one-impl
  change here too.

## Integration / e2e

No rig in this ticket — the rig-level proof of this edge is **T6's E8** (the second, dedicated-URL
fault-injection pass). `SupervisedEdgeWiringIT` runs against real Postgres via Testcontainers and is
the integration-level proof that the bean, the property default chain, and the breaker separation are
real rather than intended.

## Decisions

- **The exchange body returns empty for permanent failures instead of throwing a permanent carrier.**
  `HttpRoleDefinitionSupplier` and `TagDefinitionClient` both throw their domain exception from the body
  for a permanent failure, because their contracts are a throw. This edge's contract is
  *fail-closed-to-empty* (`mx-1ce7d5`: the base-scope SPI shape, unlike the tri-state role supplier), so
  returning empty is the direct expression of it — **and** it is what keeps a permanent failure off the
  breaker, which `mx-951d2f` requires. Throwing a permanent carrier and catching it outside would have
  recorded a breaker failure for what is a decision.
- **The client is not a second `GovernedScopeResolver` implementation.** The SPI is single-bean by
  construction and the membership resolver already owns it; more importantly the two sets must be
  reduced against each other (`supervised := S \ M`) before either is used, and only the list authorizer
  sees both. Composing beside the resolver is also what keeps this slice out of the library, whose SPI
  contract-text revision ADR 0029 §9 explicitly defers.
- **`supervised-base-url` defaults to the property, not to the environment variable.** See review
  finding 1 — the env-var form looks equivalent and is not.
- **No seam deviation to report.** Every artifact was verified against the source before being built on:
  `HttpGovernedScopeResolver`'s classification and constructor shape, `CallGuard.call`'s three-argument
  signature and its documented "only a *thrown* fault records on the breaker" semantics (read in
  `Resilience4jCallGuard.call`, not assumed), `Resilience4jCallGuard.breaker()`'s test visibility,
  `RetryableClassification.retryableStatus` (429 + 5xx), `OpaAbacProperties.Resilience#getResolve`, and
  T1's `/internal/supervised-targets` contract (always `200`, a possibly-empty array) as
  `STATUS-01.md` records it.

## Commit

`feat(supervised-scope): T4 the SupervisedScopeClient HTTP edge, fail-closed and resilience-wrapped`
— on `feature/void3110/supervised-scope`.
