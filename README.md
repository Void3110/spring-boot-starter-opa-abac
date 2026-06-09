# spring-boot-starter-opa-abac

Production-grade **ABAC authorization for Spring Boot** powered by **Open Policy Agent (OPA)** —
plus a **runnable example** that demonstrates the whole picture end to end.

> Fine-grained, attribute-based access control with hierarchical resources, batch evaluation,
> and partial-evaluation data filtering — the features real applications need and existing
> libraries don't provide.

[![CI](https://github.com/Void3110/spring-boot-starter-opa-abac/actions/workflows/ci.yml/badge.svg)](https://github.com/Void3110/spring-boot-starter-opa-abac/actions/workflows/ci.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

## Status

🚧 **Active development** — the library is taking shape slice by slice, each one layered onto the
runnable example and proven end-to-end (unit + Testcontainers ITs + `opa test` + a newman gateway matrix).

**Shipped so far:**

- **Domain-model foundation** — a secured-entity base (UUID id, audit, optimistic-lock `@Version`,
  JSONB resource tags) + a safe locked-write `mutate()` path.
- **Library spine** — a fail-closed OPA client (`HttpOpaClient`, JDK `HttpClient`), JWT→subject
  extraction, and a **role-definition-driven** `@OpaPreAuthorize` enforcement path.
- **Team-based authorization** — an example `user-management-service` resolving a caller's effective role
  from real team membership (role ≠ grant, owner-on-create, the subset/no-self-escalation rule, transfer).
- **Dynamic tag dictionary** — runtime-editable tag keys + tag-based grants matched in Rego (`some in` /
  `every`, ANY_OF / ALL_OF).
- **Partial-evaluation data filtering** — OPA's Compile API → a JPA `Specification` over JSONB, so a list
  endpoint returns only the rows a subject may see (filtered in SQL, fail-closed to an empty page).
- **N-level hierarchical authorization** — a grant on a Catalog governs a Category/Product nested under it,
  N levels deep (opt-in per relation, deny-overridable, fail-closed; an `ltree` materialized-path resolver
  + atomic re-parent). See [`docs/guides/HIERARCHICAL-AUTHORIZATION.md`](docs/guides/HIERARCHICAL-AUTHORIZATION.md).
- **Hierarchy-aware list filtering** — an inheritable ancestor grant *widens a list* in SQL
  (`scope AND (tagResidual OR subtreeSpec) AND notDenied`), composed so the widening can never escape the
  caller's scope and a leaf deny still overrides it.
- **RFC-7807 error contract** — every error is `application/problem+json` with a typed, library-owned
  `errorCode` vocabulary (app-extensible), plus `Location` on creates — asserted end-to-end by the
  newman matrices.

**Next:** the pagination envelope (5.95), then an action-affordance map (Phase 6). The technical
plan lives in [`docs/to-do/planning/POC-ROADMAP/`](docs/to-do/planning/POC-ROADMAP/POC-ROADMAP.md).

> **Not yet published to Maven Central** — the API is still moving as slices land. The full picture
> (architecture, ADRs, guides) is in [`docs/`](docs/README.md).

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

```
                     ┌──────────┐   OIDC    ┌──────────┐
   browser / client  │  APISIX  │──────────▶│ Keycloak │
        ──────────▶  │ (gateway)│           └──────────┘
                     │          │  opa check  ┌──────────┐
                     │          │────────────▶│   OPA    │
                     └────┬─────┘             └──────────┘
                          │ proxied request (JWT)        ▲ traces
                          ▼                              │
              ┌───────────────────────────┐        ┌─────────┐
              │ catalog-management-service │        │ Jaeger  │
              │  (ABAC checks via starter) │        └─────────┘
                          │         ╲
                          ▼          ╲ effective role / tags
                     ┌──────────┐   ┌─────────────────────────┐
                     │ Postgres │   │ user-management-service │
                     └──────────┘   │  (roles + tag dictionary)│
                                    └─────────────────────────┘
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

## How this repo is built — AI-assisted engineering

Beyond the library, this repo is a deliberate, **studyable case study in high-autonomy AI-assisted
engineering**. Every slice is shipped through the same documented loop, and the prompts and outcomes are
kept verbatim so the *method* — not just the result — is on display:

```
① PLAN          ② DECOMPOSE              ③ AUTONOMOUS IMPLEMENT       ④ REVIEW / SHIP
chat + grill-me  design → ordered tickets  one agent runs the prompt,   /deep-review (multi-lens,
→ ADRs, design   + QA cases + a verbatim   ticket by ticket,            adversarial verify) →
                 autonomous prompt         checkpoint-gated,            PR → CI → merge →
                                           fail-closed                  record the run retrospective
```

Each slice's planning package (`00-DESIGN`, the ordered tickets, the **verbatim**
`AUTONOMOUS-IMPLEMENTATION-PROMPT.md`, and per-ticket `STATUS` notes) is preserved under
[`docs/to-do/`](docs/to-do/) so a reader can see how the work was reasoned about, handed off, and verified.

**The tooling that powers each phase** (with upstream credits and the orchestration patterns each one
instantiates):

| Phase | Tooling |
|-------|---------|
| ① Plan | **grill-me** (fork-resolving interview) → immutable ADRs + a design |
| ② Decompose | **slice-planner** → the ordered tickets, QA cases, and the autonomous prompt |
| ③ Implement | one agent runs the prompt, checkpoint-gated, with an architecture-review gate before every validation |
| ④ Review | **deep-review** — a multi-lens, adversarial workflow (fan-out → refute → synthesize) |
| All phases | **Mulch** (a per-repo expertise store, primed before / recorded after) + **LSP** (`jdtls`) code intelligence |

Two quality ideas run through it: **fail-closed is the load-bearing invariant** every slice is checked
against, and an **autonomous-run retrospective** is recorded for each slice (was it a clean run or did it
pause to ask — and what should planning have pre-resolved) so the *next* slice's planning gets sharper.

> The full method — the prompt template, the hard rules, the tooling stack with credits, and a portable
> review-harness template — is documented in
> [`docs/guides/AUTONOMOUS-IMPLEMENTATION-FLOW.md`](docs/guides/AUTONOMOUS-IMPLEMENTATION-FLOW.md).

## Requirements

- Java 21+
- Spring Boot 3.4+
- A container runtime — Docker or podman (for the example infrastructure and the integration tests)
- **Open Policy Agent (OPA) 1.x** — the decision engine the library calls; the local rig runs it for you
- **PostgreSQL** — the example uses Postgres-specific features (JSONB tags, `ltree` materialized paths)

## Documentation

The full architecture, decision records, and per-feature guides live in [`docs/`](docs/README.md):
- **Guides** — [`docs/guides/`](docs/guides/) (ABAC spine, team/tag/data-filtering/hierarchical authz, e2e)
- **Architecture & ADRs** — [`docs/architecture/`](docs/architecture/)
- **Roadmap** — [`docs/to-do/planning/POC-ROADMAP/`](docs/to-do/planning/POC-ROADMAP/POC-ROADMAP.md)
- **AI-assisted workflow** — [`docs/guides/AUTONOMOUS-IMPLEMENTATION-FLOW.md`](docs/guides/AUTONOMOUS-IMPLEMENTATION-FLOW.md) (the plan → implement → review method this repo is built with) + the portable [`docs/code-review/DEEP-REVIEW-TEMPLATE.md`](docs/code-review/DEEP-REVIEW-TEMPLATE.md)

## License

[Apache License 2.0](LICENSE)
