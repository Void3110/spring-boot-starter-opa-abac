---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/spring
---

# STATUS — T1: Walking skeleton: `example-mcp-server` + declared `@McpTool` catalog proxies (no authz)

**Status:** ✅ DONE

## What shipped

A new Gradle module, `example-mcp-server` — a Spring AI MCP server whose four `@McpTool` methods proxy
the **existing** catalog REST API with the caller's own bearer. No authorization yet: this ticket lands
the surface the next five gate.

| Area | Files |
|---|---|
| Build | `settings.gradle.kts` (+ the example include), `gradle/libs.versions.toml` (`springAi = 2.0.0` + two library entries), `example-mcp-server/build.gradle.kts` |
| App | `McpServerApplication`, `config/SecurityConfig`, `config/McpServerProperties`, `config/CatalogToolConfiguration`, `config/ToolRegistrationConfiguration`, `src/main/resources/application.yml` |
| Tool surface | `tool/CatalogTools` (4 `@McpTool` methods), `tool/ToolDescriptor`, `tool/ToolRegistry`, `tool/ToolRegistryValidator`, `tool/ToolCallClassifier` (contract-only) |
| Outbound edge | `tool/CatalogApiClient`, `tool/CallerBearerSupplier`, `tool/CatalogApiErrorTranslator`, `tool/ToolInvocationException`, `tool/ToolErrorLayer` |

The four tools — `list_catalogs`, `get_catalog`, `list_categories`, `get_product` — each declare an action
verb, a permission category and a risk-tag set, drawn from the shipped vocabulary in
`infra/opa/policies/permission_categories.json` so the tool-gate (T3) derives the ceiling through the
**same** expansion the rest of the repo uses.

## Tests

`./gradlew :example-mcp-server:test` — **37 passed, 0 failed.**

| Case | Where |
|---|---|
| **U1** — every advertised tool declares a non-blank action, category and ≥1 risk tag; the declared triple is served verbatim; an undeclared name resolves to empty, never a permissive default | `McpToolSurfaceTest`, `ToolRegistryTest`, `ToolDescriptorTest` |
| **U2** — registration-time validation: a blank category/action/name/risk-tag fails, naming the tool; an advertised-but-undeclared tool **fails startup**; a declared-but-unadvertised tool fails too | `ToolDescriptorTest`, `ToolRegistryValidatorTest` |
| **U3** — the classifier seam is an interface with **no implementation bean** in the context | `McpToolSurfaceTest` |
| **I1** — the catalog stub receives exactly one request, `Authorization: Bearer <caller>` **byte-for-byte**, and **no** role/capability/act/chain/subject/principal header; path segments are percent-encoded | `CatalogApiClientTest`, `CallerBearerSupplierTest` |
| **I2** — a stub 403 → the `target-gate`-labelled advisory error, upstream `errorCode` preserved, upstream detail **not** leaked | `CatalogApiClientTest` |
| **I3** — 5xx, connection-refused, read-timeout, malformed body and empty body → structured errors, no stack trace, no transport detail, bounded by the configured timeout | `CatalogApiClientTest` |

## Architecture review + refactor

**Fail-closed.** Each new edge and where it lands:

| Edge | Failure | Lands on |
|---|---|---|
| `CallerBearerSupplier` | no request / no header / non-Bearer scheme / blank token | throws — and it throws while the request is being *built*, so **no anonymous downstream call is ever issued** |
| `CatalogApiClient` | IOException / interrupt / timeout | `catalog-unreachable`, bounded by `read-timeout` |
| `CatalogApiClient` | non-2xx | translated; 403 → `target-gate` |
| `CatalogApiClient` | blank or unparseable 200 | `catalog-malformed-response` — deliberately **not** an empty map, which a caller would read as "no catalogs" |
| `ToolDescriptor` | blank action/category/name, empty risk tags | construction throws → startup fails → the tool is never exposed |
| `ToolRegistry.find` | unknown name | `Optional.empty()`, never a permissive default |
| `ToolRegistryValidator` | surface/registry drift, **either direction** | startup fails |

T1 introduces **no kill-switch**, so "no OFF state wider than ON" is vacuously true here rather than
skipped; T4 owns the first one. The absent-vs-malformed actor asymmetry is T2's — nothing in T1 reads an
actor claim.

**Security — the widening that would matter here** is the outbound request: anything beyond the caller's
bearer (a role, capability, chain or acting-as header), or a minted/exchanged/rewritten token, would make
the catalog service trust this server — the caller-supplied-role shape B4 removed. It cannot happen: the
client sets exactly `Authorization` and `Accept`, and `forwardsTheCallerBearerVerbatimAndNothingElse`
asserts on the **received header-name set**, not just on the two headers it expects. Second widening:
argument injection into the downstream path — `segment()` percent-encodes (and re-encodes `+` to `%20`,
which `URLEncoder`'s form encoding gets wrong for paths), asserted against `c 1/../../admin`. Third: leaking
internals into an advisory error read by a model — only the upstream `errorCode` crosses; detail text,
host/port and exception types stay in the log, asserted negatively.

**Concurrency / idempotency.** This ticket gates **no mutation** — all four tools are reads and the module
owns no persistence — so the check is stated rather than skipped. Shared state: `HttpClient` (thread-safe),
`ToolRegistry` (immutable after construction, `Map.copyOf`), `CallerBearerSupplier` (stateless, reads
thread-bound request attributes). No memo exists yet (T3 adds the turn-scoped one). A replayed identical
tool call issues an identical request.

**Wiring — what the review actually found.** Every seam had a named consumer and a non-happy-path test
except one: **`SecurityConfig` was unwired-untested, and its `else` branch (no `AbacFilter` contributed)
serves `anyRequest().permitAll()`.** Nothing proved which branch the real context takes — if the starter
had not contributed the filter, the whole tool surface would have been anonymous. **Refactor applied:**
added `McpServerSecurityTest`, which asserts the `AbacFilter` bean **is** present (so the authenticated
branch is the live one), that `/mcp` and `/actuator/env` reject an anonymous caller, and that
`/actuator/health` stays open for the rig's readiness wait. This was the one substantive finding.

`ToolErrorLayer.TOOL_GATE` has no consumer in T1 by design — the decomposition lands the whole label
vocabulary here and T4 consumes the other half. `ToolCallClassifier` is the documented contract-only
exception (ADR 0028), asserted by U3.

**Boundary / additivity.** `git diff --stat` against `main`, excluding the new module, touches exactly
`settings.gradle.kts` (+4) and `gradle/libs.versions.toml` (+12) — the two build-breakers the ticket
required in this commit — plus the planning docs. **Zero** diff under `opa-abac-*/`,
`example-catalog-management-service/`, `example-user-management-service/`, `infra/opa/policies/`, and
`scripts/postman/`. No schema change anywhere.

**Module-layer separation.** `…mcp.tool` holds the tool surface and its outbound client; `…mcp.config`
holds wiring only. The tool layer evaluates no policy (there is none yet to evaluate) and resolves no
target resource beyond the ids it is handed.

**Pattern reuse.** New-module + `@ConfigurationProperties` optional wiring mirrors
[[USER-DIRECTORY-PORT]]. Two deviations, both deliberate — see *Decisions*.

**SOLID.** `CatalogApiClient` depends on `CallerBearerSupplier` and `CatalogApiErrorTranslator` rather than
inlining either, so the PEP (T4) can reuse the translator's layer vocabulary without touching transport.
`ToolRegistryValidator` takes a `ListableBeanFactory`, not the concrete context.

**Static analysis.** The local Sonar scan on the changed files returned **16 findings**; adjudicated
against the `quality-gate-sonar` FP catalog (mx-302e78), **4 were real and are fixed in this commit**:

| Rule | Where | Fix |
|---|---|---|
| S1192 | `CallerBearerSupplier` | extracted `BEARER_PREFIX` |
| S1192 | `CatalogTools` | extracted `CATALOGS_PATH` + a `catalogPath(id)` helper — which also makes it structurally impossible for an id to reach a path without going through `segment()` |
| S1612 | `McpToolSurfaceTest` | `Objects::nonNull` |
| S7467 | `CatalogApiClientTest` | unnamed pattern `catch (InterruptedException _)`, matching the repo's existing idiom |

The remaining **13 are documented by-design classes and were not re-fixed**: S5778×8 (test lambdas of the
form `() -> factory(x).method(...)`, test-code only), S112 (`SecurityFilterChain … throws Exception`,
imposed by Spring Security's builder API), S4502 (CSRF off — stateless JWT bearer behind APISIX, no
cookie-auth surface, identical posture to the other two example services), S2925 (`Thread.sleep` **inside
a mock `HttpServer` handler**, deliberately past the client's read timeout to inject the unresponsive
fault), S5976 (three separately-named fail-closed cases, each documenting a distinct edge). One is
**newly adjudicated**: S1075 on `CATALOGS_PATH` — the *host* is configurable
(`example.mcp.catalog.base-url`); the path is the catalog service's API-version contract, and making it a
property would widen the surface, not narrow it. Recorded as a new by-design class.

## Integration / e2e

`./gradlew :example-mcp-server:test` green — the in-process `HttpServer` stubs **are** the T1 integration
surface; there is no rig work until T6. `./gradlew build` green across all library modules and all three
example apps, so the new module does not break the existing Testcontainers ITs or the OpenAPI codegen.

## Decisions

1. **Spring AI 2.0.0 runs on this repo's Boot 4.0.7 — verified, not assumed.** The
   `spring-ai-starter-mcp-server-webmvc:2.0.0` POM declares `spring-boot 4.1.0`. The
   `io.spring.dependency-management` plugin imports **this repo's** Boot BOM first, so every
   `spring-boot-*` artifact resolves to 4.0.7. A context-start spike confirmed the MCP auto-configuration
   registers cleanly (15 MCP beans, `McpServerSseWebMvcAutoConfiguration` + the annotation scanner) before
   any T1 code was written. A repo-wide 4.1.x bump stays a separate slice.
2. **The MCP server excludes `opa-abac-spring-data`.** The starter `api`-exposes it, which
   `api`-exposes `spring-boot-starter-data-jpa`, which makes Boot auto-configure a `DataSource` and fail
   with *"Failed to determine a suitable driver class"*. This service owns no persistence, so the module is
   excluded and every JPA auto-configuration backs off on an **absent** classpath rather than being
   disabled by name — the "depend on what you use" path the starter's own build script documents for
   non-JPA adopters.
3. **JDK `HttpClient` + Jackson, not `RestClient`** (a deviation from the decomposition's wording). All
   three existing outbound clients in this repo — `TagDefinitionClient`, `HttpRoleDefinitionSupplier`,
   `HttpOpaClient` — use the JDK client, and `TagDefinitionClient`'s javadoc records the choice explicitly
   ("no Feign/RestTemplate/WebClient"). Introducing a fourth transport idiom for one module is not worth
   the inconsistency; the test shape (in-process `HttpServer`) is identical either way.
4. **The catalog call is not `CallGuard`-wrapped** (a considered deviation from the ★review's pattern-reuse
   prompt). B3's three edges are all **authorization-input** reads — the OPA decision, the role resolve,
   the tag dictionary — whose failure would otherwise break or widen a decision. The catalog proxy call is
   a **business-data** read: its failure is just a failed tool call, which the agent can retry itself, and
   a breaker there would add config surface and a kill-switch this ticket was not scoped for. The part of
   B3's discipline that does apply — translate at the edge, never leak a transport exception upward — is
   kept. T4's **OPA** tool-gate call consumes the shipped, already-B3-decorated `OpaClient`, so the
   discipline lands where it belongs.
5. **All four tools are reads, and the allow/deny contrast comes from the risk tier.** The slice gates no
   mutation, so `get_product` is declared `medium`-risk against the other three at `low`: a capability
   capped at `low` is narrowed without a destructive tool existing. This also supplies T5's "exactly 2 of 4"
   roster fixture.
6. **MCP spec revision confirm (the ticket's docs-only deliverable):** re-checked
   `modelcontextprotocol.io/specification/versioning` on **2026-07-28** — it still reads *"The **current**
   protocol version is 2025-11-25"*. The 2026-07-28 revision has **not** flipped to Current. The pin in
   [[00-DESIGN]] §7 is correct and unchanged; nothing in this slice moves.
7. **QA-doc numbering drift, noted not "fixed".** [[01-DECOMPOSITION]] cites T1's acceptance as *U1, U2, I1*
   while [[10-QA-TEST-CASES]] numbers T1's cases *U1–U4* and *I1–I3*. The content is consistent; only the
   labels drifted. This ticket implements the **QA doc's** T1 cases (U1, U2, U3, I1, I2, I3). Its U4
   (descriptor → OPA input mapping) genuinely belongs to **T4**, where the context builder exists — building
   an `AbacContext` here would be sneaking the gate in early, which T1 forbids.

## Commit

`feat(mcp): add example-mcp-server with declared @McpTool catalog proxies (T1)`
