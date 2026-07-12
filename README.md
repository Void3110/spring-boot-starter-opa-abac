# spring-boot-starter-opa-abac

Production-grade **ABAC authorization for Spring Boot** powered by **Open Policy Agent (OPA)** —
plus a **runnable example** that demonstrates the whole picture end to end.

> Fine-grained, attribute-based access control with hierarchical resources, batch evaluation,
> and partial-evaluation data filtering — the features real applications need and existing
> libraries don't provide.

[![CI](https://github.com/Void3110/spring-boot-starter-opa-abac/actions/workflows/ci.yml/badge.svg)](https://github.com/Void3110/spring-boot-starter-opa-abac/actions/workflows/ci.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

---

> ### 🔍 Two things to look at here
>
> **1. The library** — production-grade ABAC authorization for Spring Boot on OPA, with the features
> real apps need and existing libraries don't: hierarchical resources, batch evaluation, and
> partial-evaluation data filtering. *(The rest of this README.)*
>
> **2. The way it was built** — this repo is also a **worked case study in high-autonomy AI-assisted
> engineering**. Every feature was shipped through the same documented, self-correcting **loop** —
> `plan → decompose → autonomous-implement → review` — where each pass leaves artifacts (in **Mulch**
> and this vault) that make the next one sharper. **21 feature slices, 27 ADRs, 830 unit/IT tests +
> `opa test` 233/233 + a 14-runner gateway matrix, an ABAC gate measured at +0.79 ms p50, a 0-Critical
> security review** — all delivered this way, with the prompts and per-slice retrospectives kept
> verbatim so the *method* is inspectable, not just the result. → **[How this repo is built](#how-this-repo-is-built-ai-assisted-engineering-the-second-deliverable)** · **[`docs/methodology/`](docs/methodology/README.md)**

## Status

🚧 **Pre-publish** — every functional slice is shipped and proven end-to-end (unit + Testcontainers ITs
+ `opa test` + a newman gateway matrix), and the codebase now targets **Spring Boot 4.0 on Java 25**.
The remaining work before the **1.0** tag is a pre-publish security review and the Maven Central
publishing setup — no new features.

**Shipped:**

- **Domain-model foundation** — a secured-entity base (UUID id, audit, optimistic-lock `@Version`,
  JSONB resource tags) + a safe locked-write `mutate()` path.
- **Library spine** — a fail-closed OPA client (`HttpOpaClient`, JDK `HttpClient`), JWT→subject
  extraction, and a **role-definition-driven** `@OpaPreAuthorize` enforcement path.
- **Team-based authorization** — an example `user-management-service` resolving a caller's effective role
  from real team membership (role ≠ grant, owner-on-create, the subset/no-self-escalation rule, transfer).
- **Coarse permission categories + safe delegation** — `READ`/`WRITE`/`TAG`/`GRANT`/`CONTROL` categories
  expand to fine actions (deny-overridable), bounded by a five-tier `role_level` ceiling and a senior-tier
  subset rule; a categorized control plane for the `team:*` verbs. See [`docs/guides/PERMISSION-MODEL.md`](docs/guides/PERMISSION-MODEL.md).
- **Dynamic tag dictionary** — runtime-editable tag keys + tag-based grants matched in Rego (`some in` /
  `every`, ANY_OF / ALL_OF); tags are first-class on catalogs, categories, **and** products.
- **Partial-evaluation data filtering** — OPA's Compile API → a JPA `Specification` over JSONB, so a list
  endpoint returns only the rows a subject may see (filtered in SQL, fail-closed to an empty page).
- **N-level hierarchical authorization** — a grant on a Catalog governs a Category/Product nested under it,
  N levels deep (opt-in per relation, deny-overridable, fail-closed; an `ltree` materialized-path resolver
  + atomic re-parent). See [`docs/guides/HIERARCHICAL-AUTHORIZATION.md`](docs/guides/HIERARCHICAL-AUTHORIZATION.md).
- **Hierarchy-aware list filtering** — an inheritable ancestor grant *widens a list* in SQL
  (`scope AND (tagResidual OR subtreeSpec) AND notDenied`), composed so the widening can never escape the
  caller's scope and a leaf deny still overrides it.
- **Attribute-rich pre-authorization** — an opt-in resolver + request-scoped cache decides the gate on a
  resource's *real* attributes and ancestors, with version-guarded mutations (`409` on drift).
- **Multi-tenant isolation + self-service** — team membership is the sole access path to the hierarchy,
  with a real cross-service ownership check so team-create can't squat another user's catalog.
- **Action-affordance metadata** — a response advice attaches an `_actions` map (which actions the caller
  may perform) via one batch OPA round-trip, so a UI renders exactly the buttons the user can use.
- **User directory** — a `UserDirectory` search SPI with an optional Keycloak-admin adapter (least-privilege
  `view-users` client), fail-closed to empty when absent.
- **RFC-7807 error contract + pagination envelope** — every error is `application/problem+json` with a
  typed, library-owned `errorCode` vocabulary (app-extensible), plus `Location` on creates; every list is
  a `{count, page, perPage, items}` envelope with an exact subject-relative count, composed with the
  partial-eval filter — all asserted end-to-end by the newman matrices.
- **Cross-service HTTP resilience** — a backend-agnostic `CallGuard` (Resilience4j) with per-edge
  retry/backoff/circuit-break that makes outages rarer **without** ever re-opening the fail-closed
  outage→deny contract.

**Now on Spring Boot 4:** the whole line targets **Boot 4.0 / Framework 7 / Security 7 / Hibernate 7 /
Jackson 3** on **Java 25 / Gradle 9**, as a single artifact line (see [ADR 0026](docs/architecture/adr/0026-spring-boot-4-single-line-port.md)).

**Next:** the pre-publish gauntlet is essentially clear — security review (0 Critical; the findings
fixed), a full-history secret scan (clean), a dependency CVE sweep (clean), and the load-test
re-baseline are all done. What remains before the **1.0** tag is the Maven Central publishing setup.
The technical plan lives in [`docs/to-do/planning/POC-ROADMAP/`](docs/to-do/planning/POC-ROADMAP/POC-ROADMAP.md).

> **Not yet published to Maven Central** — the API is settling for the 1.0 tag. The publish wiring is
> in place (six signed coordinates under `dev.dmitriikonovalov`, incl. an `opa-abac-bom`); the manual
> release steps are documented in [`RELEASING.md`](RELEASING.md). The full picture (architecture, ADRs,
> guides) is in [`docs/`](docs/README.md).

## Adopting the starter (three things you must do)

The starter exposes beans and stays out of your app's security wiring — so a bare dependency does
**nothing on its own** (fail-closed by design: no `SecurityFilterChain` is registered, and every
request is anonymous until you opt in). To actually enforce ABAC, your application must:

1. **Declare a `SecurityFilterChain` and install the `AbacFilter`.** The starter never registers a
   chain (that is the app's call); add the auto-configured `AbacFilter` bean to yours so the subject is
   extracted per request.
2. **Add `@EnableMethodSecurity`** to a `@Configuration` class. `@OpaPreAuthorize` is a method-security
   annotation — **without `@EnableMethodSecurity` every `@OpaPreAuthorize` gate is silently ignored**
   (Spring cannot let a library enable this for you). The starter logs a loud startup **WARNING** if it
   detects the annotations are wired but method security is off, so a misconfiguration can't hide.
3. **Set `opa.abac.subject.trust-forwarded-jwt=true`** — but **only** when the app sits behind a
   signature-validating gateway. The default JWT extractor does not verify signatures itself; until you
   acknowledge the gateway-trust posture it stays disabled (every request anonymous, all checks deny),
   with a startup warning explaining why. Alternatively, provide your own `AbacSubjectExtractor` bean.

See [`docs/guides/ABAC-AUTHORIZATION.md`](docs/guides/ABAC-AUTHORIZATION.md) for the full wiring and the
[example services](example-catalog-management-service/) for a working `SecurityConfig`.

## This is a monorepo

Two things live here, built together so the library and real consumers evolve in lockstep:

```
spring-boot-starter-opa-abac/
├── opa-abac-core/                       # Framework-agnostic ABAC model + OPA client (library)
├── opa-abac-spring-security/            # Spring Security integration (AuthorizationManager, @OpaPreAuthorize)
├── opa-abac-spring-data/                # Partial-eval → JPA Specification filtering + ltree hierarchy
├── opa-abac-spring-boot-starter/        # Auto-configuration (the published starter)
├── example-catalog-management-service/  # E-commerce product catalog REST service (the app we secure)
├── example-user-management-service/     # Users/teams/role-definitions/tag-dictionary (drives the ABAC attributes)
├── infra/                               # The local rig: APISIX, Keycloak, OPA (+ policies), Jaeger
├── scripts/postman/                     # Newman e2e matrices (the through-the-gateway proofs)
└── docs/                                # Architecture, ADRs, guides, per-slice planning packages
```

The `opa-abac-*` modules are the publishable library. The two `example-*` services are
demonstrations and are **not** published.

### The architecture (running today via `deploy.sh`)

```mermaid
flowchart TD
    Client["browser / client"] --> APISIX["APISIX<br/>(gateway)"]
    APISIX -- "OIDC" --> Keycloak["Keycloak"]
    APISIX -- "opa check" --> OPA["OPA"]
    APISIX -- "proxied request (JWT)" --> Catalog["catalog-management-service<br/>(ABAC checks via starter)"]
    Catalog --> Postgres["Postgres"]
    Catalog -- "effective role / tags" --> UserSvc["user-management-service<br/>(roles + tag dictionary)"]
    Catalog -. "traces" .-> Jaeger["Jaeger"]

    classDef gw fill:#eef2ff,stroke:#4f46e5,color:#1e1b4b;
    classDef svc fill:#ecfdf5,stroke:#059669,color:#064e3b;
    classDef infra fill:#f1f5f9,stroke:#475569,color:#0f172a;
    class APISIX gw;
    class Catalog,UserSvc svc;
    class Keycloak,OPA,Postgres,Jaeger infra;
```

The `user-management-service` (teams, role definitions, a dynamic tag dictionary) supplies the
attributes the ABAC decisions are made with — and dogfoods the starter to secure its own API.

## The example: catalog-management-service

An e-commerce **Product Catalog Management** service. Simple, but hierarchical — exactly the
shape ABAC needs to show off:

- **Catalog** → **Category** (self-referencing parent/child tree) → **Product**

Built with the **vanilla** `org.openapi.generator` Gradle plugin
(`generatorName = spring`, `interfaceOnly`): the OpenAPI spec generates API interfaces + DTOs,
and we write the `@RestController` implementations. Persistence is Postgres via Spring Data JPA,
schema managed by Liquibase. The service is **secured by default** — every `/api/v1/**` request
requires an authenticated subject and a real OPA decision, so the meaningful way to drive it is
**through the rig** (below). That's deliberate: an authorization showcase whose example runs open
would undercut its own pitch.

### Browse it standalone (no auth, read-only exploration)

```bash
./profile.sh up        # start Postgres (Docker), host port 5433
./gradlew :example-catalog-management-service:bootRun
# Swagger UI (API browsing) at http://localhost:8080/swagger-ui.html
./profile.sh down      # stop & remove
```

Swagger UI, the OpenAPI spec, and `/actuator/health` are open, so you can explore the API surface —
but **API calls will return 401**: there's no token source and no OPA standalone. To exercise the
API, run the full rig.

> Postgres is published on host port **5433** (not 5432) to avoid colliding with other
> local Postgres instances. Override with `SPRING_DATASOURCE_URL` if needed.

### Run the full secured rig (APISIX → OPA → app → Postgres)

To see ABAC enforced end-to-end — gateway OIDC, OPA decisions, the user-service, tracing:

```bash
./profile.sh up                                          # base Postgres
ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2
# gateway at http://localhost:9085 ; then run an allow/deny matrix:
cd scripts/postman && ./run-hierarchy-matrix.sh          # or run-tests.sh / run-filter-matrix.sh / ...
ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh down
```

> The newman matrices under [`scripts/postman/`](scripts/postman/) are the through-the-gateway proofs
> (role / team / tag / data-filtering / hierarchy). Mint tokens **in-network** (APISIX validates the
> issuer as `keycloak:8888`) — the scripts handle this. See [`infra/README.md`](infra/README.md).

### Running the tests

Integration tests (`CatalogCrudIT`) run the full catalog → category → sub-category → product
CRUD walk-through against a **real Postgres 16** spun up by [Testcontainers](https://testcontainers.org),
with the real Liquibase migrations applied — so they verify the actual deployed schema, not a
substitute database. They need a container runtime.

```bash
./gradlew build          # under Docker Desktop, Testcontainers auto-detects the daemon
```

Using **podman** instead of Docker? The build auto-discovers the podman machine's API socket
(via `podman machine inspect`) and disables the privileged Ryuk reaper, so `./gradlew build`
works with no extra config. To target a specific daemon, set `DOCKER_HOST` and it takes
precedence:

```bash
export DOCKER_HOST="unix://$(podman machine inspect podman-machine-default \
  --format '{{.ConnectionInfo.PodmanSocket.Path}}')"
./gradlew build
```

GitHub Actions provides Docker out of the box, so CI runs these tests with no extra config.

## How this repo is built — AI-assisted engineering (the second deliverable)

Beyond the library, this repo is a deliberate, **studyable case study in high-autonomy AI-assisted
engineering**. The headline is not "a workflow" — it's an **engineered loop**: every slice ships through
the same documented cycle, and each pass leaves artifacts that make the *next* pass sharper. Two loops, at
two timescales:

- **Inner loop** (per ticket) — `prime → build → test → ★architecture-review+refactor → e2e → commit`.
  Unit-green is not "done": it's the *trigger* to review-and-refactor **before** the heavier validation.
  Self-correction is built into every ticket.
- **Outer loop** (across slices) — a **run retrospective** is recorded after each slice (was it a clean
  run or did it pause to ask — and what should planning have pre-resolved), and the *next* slice's planning
  reads it. The loop literally learns: an oversized slice paused once → that became a **slice-sizing gate**
  every later slice is checked against.

```
① PLAN          ② DECOMPOSE              ③ AUTONOMOUS IMPLEMENT       ④ REVIEW / SHIP
chat + grill-me  design → ordered tickets  one agent runs the prompt,   /deep-review (multi-lens,
→ ADRs, design   + QA cases + a verbatim   ticket by ticket,            adversarial verify) →
                 autonomous prompt         checkpoint-gated,            PR → CI → merge →
                                           fail-closed                  record the run retrospective ──┐
     ▲                                                                                                 │
     └─────────────── the retrospective + the accumulators feed the NEXT slice's planning ◀────────────┘
```

**What makes it a *loop*, not a pipeline, is two accumulators** — the memory that carries between passes:

- **Mulch** — the *experiential* memory (`ml prime` before a task, `ml record` after a durable insight).
  Its `autonomous-runs` domain is the outer loop's state store.
- **This vault** (`docs/`) — the *decisional* memory: immutable ADRs, living guides, per-slice `STATUS`
  notes, review notes, QA records. Phase ① reads it to know what's decided and what's still unpinned.

Each slice's planning package (`00-DESIGN`, the ordered tickets, the **verbatim**
`AUTONOMOUS-IMPLEMENTATION-PROMPT.md`, and per-ticket `STATUS` notes) is preserved under
[`docs/to-do/`](docs/to-do/) so a reader can see exactly how the work was reasoned about, handed off, and
verified — nothing is hidden behind "the AI did it."

**What the method delivered** (all shipped through this loop):

| | |
|---|---|
| **21** feature slices | each planned → decomposed → autonomously implemented → reviewed |
| **27** ADRs | every structural fork pinned as an immutable decision record |
| **830** unit/IT tests · `opa test` **233/233** · **14**-runner gateway matrix | the automated proof, real Postgres (Testcontainers) + through-the-gateway |
| **+0.79 ms** ABAC gate at p50 | measured on the real rig, statistically flat at the tail ([PERFORMANCE.md](PERFORMANCE.md)) |
| **0 Critical** security review | pre-publish 8-angle review + secret scan + CVE sweep, findings fixed |

**The tooling that powers each phase** (with upstream credits and the orchestration patterns each one
instantiates):

| Phase | Tooling |
|-------|---------|
| ① Plan | **grill-me** (Matt Pocock — a fork-resolving interview) → immutable ADRs + a design |
| ② Decompose | **decompose** (this repo's skill) → the ordered tickets, QA cases, and the verbatim autonomous prompt |
| ③ Implement | one agent runs the prompt, checkpoint-gated, with an architecture-review gate before every validation |
| ④ Review | **deep-review** — a multi-lens, adversarial workflow (fan-out → **refute** → synthesize); **security-review** for whole-surface passes |
| Across passes | **Mulch** (Jaymin West — the expertise store, primed before / recorded after) + **the vault** (the decisional accumulator) |

The orchestration shapes (fan-out, adversarial-verify, completeness-critic, loop-until-dry) are
**Anthropic's dynamic-workflows / "a harness for every task"** patterns, composed per phase.

**Fail-closed is the load-bearing invariant** every slice is checked against — no error path ever widens
access. And the loop is deliberately **human-gated**: the maintainer runs each phase and decides what
merges (an auditable loop, not a runaway flywheel) — a feature, for a method meant to be trusted.

> **The full method lives in [`docs/methodology/`](docs/methodology/README.md)** — the loop framing, the
> 4-phase lifecycle, the three failure modes it counters, and the portable, vendor-neutral phase
> [`templates/`](docs/methodology/templates/). The deep, canonical reference (the verbatim prompt skeleton
> + the lessons baked into it) is
> [`docs/guides/AUTONOMOUS-IMPLEMENTATION-FLOW.md`](docs/guides/AUTONOMOUS-IMPLEMENTATION-FLOW.md).

## Requirements

- Java 25+
- Spring Boot 4.0+
- A container runtime — Docker or podman (for the example infrastructure and the integration tests)
- **Open Policy Agent (OPA) 1.x** — the decision engine the library calls; the local rig runs it for you
- **PostgreSQL** — the example uses Postgres-specific features (JSONB tags, `ltree` materialized paths)

## Performance

What does the authorization layer cost? Measured on the real rig by the committed k6 harness
([`scripts/load/`](scripts/load/)): on the post-SB4-port stack the full ABAC gate (subject
extraction → role resolve → OPA decision) adds **≈ +0.8 ms at p50** over an identical-gateway
baseline and is statistically flat at the tail — sub-millisecond per request. Plus the partial-eval
list ceiling, the attributed per-request cross-service call counts, and fail-closed behavior under
dependency outages — see **[PERFORMANCE.md](PERFORMANCE.md)** (numbers, methodology, findings, and
the one-command rerun).

## Documentation

The full architecture, decision records, and per-feature guides live in [`docs/`](docs/README.md):
- **Guides** — [`docs/guides/`](docs/guides/) (ABAC spine, team/tag/data-filtering/hierarchical authz, e2e)
- **Architecture & ADRs** — [`docs/architecture/`](docs/architecture/)
- **Roadmap** — [`docs/to-do/planning/POC-ROADMAP/`](docs/to-do/planning/POC-ROADMAP/POC-ROADMAP.md)
- **AI-assisted methodology** — [`docs/methodology/`](docs/methodology/README.md) (the `plan → implement → review` loop this repo is built with, framed + indexed) + the deep reference [`docs/guides/AUTONOMOUS-IMPLEMENTATION-FLOW.md`](docs/guides/AUTONOMOUS-IMPLEMENTATION-FLOW.md) and the portable phase [`templates/`](docs/methodology/templates/)

## License

[Apache License 2.0](LICENSE)
