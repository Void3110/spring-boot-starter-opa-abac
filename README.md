# spring-boot-starter-opa-abac

Production-grade **ABAC authorization for Spring Boot** powered by **Open Policy Agent (OPA)** —
plus a **runnable example** that demonstrates the whole picture end to end.

> Fine-grained, attribute-based access control with hierarchical resources, batch evaluation,
> and partial-evaluation data filtering — the features real applications need and existing
> libraries don't provide.

[![CI](https://github.com/Void3110/spring-boot-starter-opa-abac/actions/workflows/ci.yml/badge.svg)](https://github.com/Void3110/spring-boot-starter-opa-abac/actions/workflows/ci.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

## Status

🚧 **Early development.** Phase 0: the example service skeleton (no auth yet) — the thing we
are going to secure. The starter library is then built up step by step and layered onto it.

## This is a monorepo

Two things live here, built together so the library and a real consumer evolve in lockstep:

```
spring-boot-starter-opa-abac/
├── opa-abac-core/                  # Framework-agnostic ABAC model + OPA client (library)
├── opa-abac-spring-security/       # Spring Security integration (AuthorizationManager, @OpaPreAuthorize)
├── opa-abac-spring-data/           # Partial-eval → JPA Specification data filtering
├── opa-abac-spring-boot-starter/   # Auto-configuration (the published starter)
└── example/
    └── catalog-management-service/ # E-commerce product catalog REST service (the app we secure)
```

The `opa-abac-*` modules are the publishable library. Everything under `example/` is a
demonstration and is **not** published.

### The target architecture (built incrementally)

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
              └───────────────────────────┘
                          │
                          ▼
                     ┌──────────┐
                     │ Postgres │
                     └──────────┘
```

A separate `user-service` (role definitions + a dynamic tag dictionary) is introduced later
to drive the ABAC attributes.

## The example: catalog-management-service

An e-commerce **Product Catalog Management** service. Simple, but hierarchical — exactly the
shape ABAC needs to show off:

- **Catalog** → **Category** (self-referencing parent/child tree) → **Product**

Built with the **vanilla** `org.openapi.generator` Gradle plugin
(`generatorName = spring`, `interfaceOnly`): the OpenAPI spec generates API interfaces + DTOs,
and we write the `@RestController` implementations. Persistence is Postgres via Spring Data JPA,
schema managed by Liquibase. **No authentication yet** — this is the insecure baseline we secure.

### Run it

```bash
./profile.sh up        # start Postgres (Docker), host port 5433
./gradlew :example-catalog-management-service:bootRun
# Swagger UI at http://localhost:8080/swagger-ui.html
./profile.sh down      # stop & remove
```

> Postgres is published on host port **5433** (not 5432) to avoid colliding with other
> local Postgres instances. Override with `SPRING_DATASOURCE_URL` if needed.

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

## Requirements

- Java 21+
- Spring Boot 3.4+
- A container runtime — Docker or podman (for the example infrastructure and the integration tests)
- An OPA server (added in a later phase)

## License

[Apache License 2.0](LICENSE)
