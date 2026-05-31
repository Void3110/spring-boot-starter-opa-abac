---
tags:
  - status/planned
  - type/index
  - area/abac
  - area/spring-security
  - area/spring
---

# Library spine — OPA client, extraction, role-definition-driven `@OpaPreAuthorize`

> **Status:** Planned — design + decomposition complete; not yet implemented. The work runs on
> `feature/void3110/library-spine` via the [[AUTONOMOUS-IMPLEMENTATION-PROMPT]].
> Second load-bearing slice of [[POC-ROADMAP]] **Phase 3 (library spine)**, after
> [[DOMAIN-MODEL-FOUNDATION]].

This folder is the full work package for the **authorization spine**: the library pieces that turn a
Keycloak-authenticated request into a real, fine-grained ABAC decision made by OPA — and the example
app's adoption of them. It is written to be **implemented autonomously** — the design, the work
breakdown, and a self-contained [[AUTONOMOUS-IMPLEMENTATION-PROMPT]] are all here.

## What this slice delivers

The end-to-end spine, single-decision only:

```
HttpOpaClient (JDK HttpClient + Jackson, in opa-abac-core, Spring-free, fail-closed)
  → AbacSubjectExtractor + AbacFilter (Bearer JWT → Subject, custom Authentication)   [spring-security]
  → @OpaPreAuthorize + OpaPreAuthorizeAuthorizationManager (role-definition-driven)   [spring-security]
  → starter auto-config wires every bean (conditional + overridable)                  [starter]
  → catalog example adopts it: SecurityConfig + a demo RoleDefinitionSupplier + a real
    per-type rego policy; the throwaway APISIX Lua enricher is retired.
```

- **`HttpOpaClient`** — a zero-extra-dependency `OpaClient` on the JDK `HttpClient`; POSTs
  `{"input": …}` to a per-type OPA document and reads the decision; **fails closed** on every error.
- **`RoleDefinition` + `RoleDefinitionSupplier`** — the authorization *backbone*: decisions are driven
  primarily by the caller's **role definition** (`code` + `attributes` + `permissions`), with JWT tags
  as an override. The library ships the SPI + a no-op default; the catalog app ships a **static demo
  supplier**; Phase 4's user-management-service swaps in an HTTP-backed one (a single-bean change).
- **`AbacSubjectExtractor` + `AbacFilter`** — Spring-native identity extraction: the Bearer JWT becomes
  an `AbacContext.Subject` in the `SecurityContextHolder`. **Trusts the gateway** for signature
  validation; does structural + `exp` checks only.
- **`@OpaPreAuthorize` + `OpaPreAuthorizeAuthorizationManager`** — the headline enforcement: a method
  annotation that names an action + a (type-level) resource, builds the `AbacContext` (subject + role
  definition + resource), calls OPA, and denies (`AccessDeniedException`) — fail-closed.
- **Example adoption** — the catalog app installs the filter, annotates its controllers, supplies demo
  role definitions, wires `AuditorAware` to the real principal, and runs a real per-type rego policy;
  an e2e matrix proves *viewer can read, viewer cannot write, editor can write* through the gateway.

## File glossary

| File | Role |
|------|------|
| [`LIBRARY-SPINE.md`](LIBRARY-SPINE.md) | This index. |
| [`00-DESIGN.md`](00-DESIGN.md) | The design: the spine, the `RoleDefinition`/SPI backbone (demo→Phase-4 swap), per-type rego, the two-layer gateway↔app model, the signature-trust posture, fail-closed, considered-&-rejected. |
| [`01-DECOMPOSITION.md`](01-DECOMPOSITION.md) | The ordered work list — seven tickets, each with Goal / Deliverables / Acceptance / What-NOT-to-touch. **The implementer's work list.** |
| [`AUTONOMOUS-IMPLEMENTATION-PROMPT.md`](AUTONOMOUS-IMPLEMENTATION-PROMPT.md) | Self-contained prompt to implement this package autonomously, ticket by ticket, with a review gate and checkpoints. |
| [`10-QA-TEST-CASES.md`](10-QA-TEST-CASES.md) | The cases the unit + integration + e2e work must satisfy. |
| `STATUS-01.md` … `STATUS-07.md` | One per ticket — filled in at each checkpoint during the run (what shipped, tests, review + refactor, integration/e2e, decisions, commit). |

## Tickets (status)

| # | Ticket | Status | Status note |
|---|--------|--------|-------------|
| 1 | Core: `HttpOpaClient` + `RoleDefinition`/SPI + policy-path resolver | ☐ planned | `STATUS-01.md` |
| 2 | Security: `AbacSubjectExtractor` + `AbacFilter` + `AbacAuthentication` | ☐ planned | `STATUS-02.md` |
| 3 | Security: `@OpaPreAuthorize` + authorization manager + role-def wiring | ☐ planned | `STATUS-03.md` |
| 4 | Starter: auto-configuration (conditional + overridable) | ☐ planned | `STATUS-04.md` |
| 5 | Example: security chain, demo role defs, annotations, per-type rego, retire enricher | ☐ planned | `STATUS-05.md` |
| 6 | Infra: realm users + roles for the allow/deny matrix | ☐ planned | `STATUS-06.md` |
| 7 | E2E (allow/deny matrix) + docs + roadmap/Mulch | ☐ planned | `STATUS-07.md` |

## Critical path

```
1 (HttpOpaClient + RoleDefinition + resolver)   ─┐
2 (extraction: extractor + filter + auth)        ─┤  [1 and 2 are independent; land either first]
        └──────────────┬──────────────────────────┘
3 (@OpaPreAuthorize + manager)        [needs OpaClient from 1 + Subject from 2]
  └─> 4 (starter wires every bean)    [needs 1–3]
        └─> 5 (example adopts it)      [needs the starter from 4]
              └─> 6 (realm users)      [needs the app enforcing, to make the matrix meaningful]
                    └─> 7 (e2e + docs) [needs the rig with users + the app + the rego]
```

**T1 + T2 are the standalone library foundation** and are independently testable (pure unit tests, no
app). If only a short window is available, landing 1 + 2 + 3 delivers the reusable spine; 4–7 layer it
onto the example.

## Conventions

- **Clean-room IP boundary** — original neutral names only; never copy proprietary source, names, or
  docs. The prior platform is study-only. Root [`CLAUDE.md`](../../../../CLAUDE.md) → "IP Boundary".
- **Commit identity** — `Void3110 <void31102025@gmail.com>` (repo-local). One focused commit per
  ticket; `Co-Authored-By: Claude` trailer welcome.
- **No push** — local + the feature branch only; the maintainer pushes.
- **`opa-abac-core` stays Spring-free**; the OPA client uses the JDK `HttpClient`.
- **Fail-closed everywhere** — any OPA error/timeout/ambiguity denies.
- **Docs conventions** — [`TAG-SYSTEM.md`](../../../TAG-SYSTEM.md): one `status/`, one `type/`,
  ≥1 `area/`; `UPPER-KEBAB-CASE.md` filenames; `[[wikilinks]]` within the vault.

## Workflow-as-artifact

This package is also a deliberate **case study of the maintainer's AI-assisted workflow**: the
[[AUTONOMOUS-IMPLEMENTATION-PROMPT]] is kept verbatim and the `STATUS-0N.md` notes track each ticket's
outcome (what shipped, what the review gate found, the commit). On ship the folder moves to
`docs/to-do/implemented/` with a "Shipped" banner — kept alongside [[DOMAIN-MODEL-FOUNDATION]] so the
"plan → autonomous-implement → test → review" runs can be compared.

## Related

- Roadmap: [[POC-ROADMAP]] (Phase 3)
- Prior slice this builds on: [[DOMAIN-MODEL-FOUNDATION]]
- Pattern guides this work is checked against / produces: [[DOMAIN-MODEL]] (the `AbacDataObject`
  resource side), and the new `ABAC-AUTHORIZATION` + `TWO-LAYER-AUTHORIZATION` guides (ticket 7).
- Next example app that supplies real role definitions + attributes: [[USER-MANAGEMENT-SERVICE]] (Phase 4)
