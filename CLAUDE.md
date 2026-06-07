# spring-boot-starter-opa-abac

Project instructions for Claude. Read this first.

## What this is

A **public, open-source monorepo** with two things built together:

1. **The starter library** (`opa-abac-*`) — production-grade **ABAC authorization for Spring Boot**
   backed by **Open Policy Agent (OPA)**. Fills a real gap: Spring Security has no native ABAC, and
   no existing library offers a comprehensive, Spring-native OPA integration (hierarchical resources,
   batch evaluation, partial-evaluation → JPA data filtering, `@OpaPreAuthorize`, clean auto-config).

2. **A runnable example** (`example/`) — a `catalog-management-service` (e-commerce product catalog:
   Catalog → Category tree → Product) that we secure **step by step** to demonstrate the whole
   concept end to end: APISIX (OIDC + OPA + tracing) → service (ABAC checks) → Postgres. A
   `user-service` (role definitions + a **dynamic** tag dictionary) is added later to drive the
   ABAC attributes.

The library is publishable to Maven Central; everything under `example/` is a demo and is **not** published.

## ⚠️ IP Boundary — strict clean-room

This repo is **public** and a **clean-room reimplementation**. The author has built a mature ABAC+OPA
framework inside a proprietary platform; that experience informs the design, but:

- **Never copy** source, comments, doc text, identifiers, package names, or config from any proprietary
  source into committed files here.
- Reference materials below are **local, for study only**. Read them to understand patterns; then write
  fresh, generic code/docs with neutral names (this project's domain is product catalogs, not the source's).
- No company names, internal hostnames, tokens, ticket ids, or internal URLs in committed files.
- When in doubt, generalize. The public artifact must stand on its own.

## Build / test / run

Java 21 · Spring Boot 3.4 · Gradle 8.12 (wrapper) · vanilla `org.openapi.generator`.

```bash
./gradlew build        # all library modules + example app + OpenAPI codegen + integration tests
./gradlew test         # integration tests only
./profile.sh up        # start Postgres (Docker/podman) on host port 5433
./gradlew :example-catalog-management-service:bootRun   # run the example app
./profile.sh down      # stop infra
```

### Container runtime for tests (important)

Integration tests (`CatalogCrudIT`) run against **real Postgres via Testcontainers** — never H2.
The build **auto-discovers a podman machine socket** (`podman machine inspect …`) and disables the
Ryuk reaper, so `./gradlew build` works under podman with no config. An explicit `DOCKER_HOST` wins.
Under Docker Desktop, Testcontainers auto-detects. CI (GitHub Actions) provides Docker. If tests
fail with "Could not find a valid Docker environment", a daemon isn't reachable — that's environment,
not code.

## Layout

```
opa-abac-core/                  # framework-agnostic ABAC model + OPA client (no Spring dep!)
opa-abac-spring-security/       # Spring Security integration (AuthorizationManager, @OpaPreAuthorize)
opa-abac-spring-data/           # partial-eval → JPA Specification data filtering
opa-abac-spring-boot-starter/   # auto-configuration (the published starter)
example-catalog-management-service/   # the app we secure (Postgres + Liquibase + OpenAPI codegen)
compose.yaml · profile.sh       # local infra (Postgres now; Keycloak/APISIX/OPA/Jaeger added incrementally)
docs/                           # Obsidian vault — see docs/README.md
.claude/skills/                 # rego-skill, mulch, deep-review
.mulch/                         # project expertise store (ml CLI)
```

`opa-abac-core` must stay **free of any Spring dependency**. Module deps flow one way:
`core` ← `spring-security`/`spring-data` ← `starter`.

## Incremental plan (build the example, then secure it)

1. **Phase 0 (done):** catalog-management-service — CRUD over the hierarchy, Postgres + Liquibase, **no auth**.
2. Add infra incrementally to `compose.yaml`: **Keycloak** (realm) → **APISIX** (OIDC route) → **OPA**
   (policies) → **Jaeger** (tracing).
3. Build the **library** step by step and layer it onto the example: OPA client → `AbacContext` extraction
   → `OpaAuthorizationManager` → `@OpaPreAuthorize` → batch eval → partial-eval data filtering.
4. Add **user-service** with role definitions + a **dynamic** tag dictionary (the source platform hardcodes
   tags; here we do it properly).

Track feature work under `docs/to-do/planning/<FEATURE>/`; move to `implemented/` when shipped.

## Documentation

`docs/` is an **Obsidian vault** (`.obsidian/` is gitignored). Start at [`docs/README.md`](docs/README.md).
- Architecture: [`docs/architecture/`](docs/architecture/)
- Guides: [`docs/guides/`](docs/guides/)
- API reference: [`docs/api/`](docs/api/)
- Code review: [`docs/code-review/`](docs/code-review/)
- Tags: [`docs/TAG-SYSTEM.md`](docs/TAG-SYSTEM.md)

## Mulch (expertise store)

Project expertise lives in `.mulch/`. Before a non-trivial task: `ml prime <domain>` or
`ml search "<query>"`. After a durable insight: `ml record <domain> --type <type> …` then `ml sync`.

> **The swept-staged trap.** `ml sync` commits whatever is staged. **Before `ml sync`, run
> `git restore --staged .`** so the sync commit touches `.mulch/` only — otherwise it sweeps unrelated
> staged code into the "mulch: update expertise" commit.

### Domains

| Domain | Holds |
|--------|-------|
| `opa-abac` | The cross-cutting technical store — patterns/failures/decisions about the library + rig. |
| `rego-policy` · `spring-security-integration` · `spring-data-filtering` · `api-design` | Surface-specific technical expertise; prime the one matching the change. |
| `code-review-process` | How reviews are run (the `/deep-review` process meta). |
| **`autonomous-runs`** | **Per-slice record of how the autonomous *run itself* went** — see below. |

### `autonomous-runs` — the run-retrospective domain (feeds planning)

This domain is **process-level, not technical**: one record per slice's autonomous-implementation run,
capturing **how the run went** so the *next* slice's planning/decomposition can pre-empt what stopped or
slowed the last one. The `opa-abac` domain answers "what did we learn about the code?"; this one answers
**"was the run a clean full-success, or did it have to pause and ask — and what should planning have
pinned so it wouldn't?"**

**When to record:** at the **end of a slice run** (flow phase ④, after `/deep-review`, before the folder
moves to `implemented/`). One `reference` record per run, `--outcome-status` set:

```bash
ml record autonomous-runs --type reference \
  --name "Run: <SLICE> (<N> tickets)" --classification observational \
  --outcome-status <success|partial|failure> \   # success=fully autonomous; partial=paused-and-asked; failure=blocked
  --description "OUTCOME … PAUSE-CAUSE … FRICTION … PLANNING-GAP->FIX … QA …"
git restore --staged . && ml sync
```

**Each record captures (in the description, in this order):**
1. **OUTCOME + PAUSE-CAUSE** — full-success / paused-and-asked / blocked; if it paused, the *class* of
   fork that stopped it (`undecided externally-visible behavior` · `missing acceptance detail` ·
   `rig/environment gotcha` · `design fork the docs didn't cover`) + the ticket number.
2. **CHECKPOINT / TICKET FRICTION** — any ticket that needed a substantive refactor at the ★ review gate,
   or where fix-until-green looped, or a build-breaker that had to land in the same commit. (Decomposition
   that was too coarse/fine shows up here.)
3. **PLANNING-GAP → FIX** *(the keystone)* — for each pause/friction, what the **planning (①) or
   decomposition (②)** phase *should have pre-resolved* so it won't recur. This is the actionable loop
   back into `grill-me` / `00-DESIGN` / the slice's acceptance cases.
4. **QA** — did a post-run `/deep-review` find issues the run's own gate missed? (laziness signal.)

**Prime it when planning a new slice.** During phase ① (grill-me) and phase ② (slice-planner), run
`ml prime autonomous-runs` and explicitly ask: *which fail-open/contract semantics are unpinned?* and
*which rig gotchas from prior runs apply here?* The synthesis record (`type pattern`,
"the two recurring planning-gap classes") is the one to read first — across the first six slices, the
single recurring pause/friction class was **"design left a fail-open/contract semantic unpinned,"**
followed by **"a rig/test-harness gotcha discovered mid-run."** Pre-resolving those in design is what
converts a paused run into a full-success run.

See [`docs/guides/AUTONOMOUS-IMPLEMENTATION-FLOW.md`](docs/guides/AUTONOMOUS-IMPLEMENTATION-FLOW.md) for
the full plan → implement → review method this domain measures.

## Commit identity

This repo uses a **personal** identity (set repo-local, not global):
`Void3110 <void31102025@gmail.com>`. Do **not** commit with the corporate identity. Verify with
`git config --local user.email` before committing. `Co-Authored-By: Claude` trailers are welcome
in this personal repo.

## Local-only notes

Machine-local and IP-sensitive material (local reference paths to study from, local skill
notes) lives in `CLAUDE.local.md`, which is **gitignored** and never committed. If you don't
have that file, you're missing the local study pointers — ask the maintainer. Per the
IP Boundary above, those pointers must never be reproduced in committed files.
