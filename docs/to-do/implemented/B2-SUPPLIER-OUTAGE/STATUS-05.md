---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/security
  - area/spring
---

# STATUS — T5: e2e + the headline IT + docs + slice record

**Status:** ✅ DONE

## What shipped

- **The headline IT** — `SupplierOutageGateIT` (real Postgres via Testcontainers, `catalog.role-source=none`,
  resolution off): a subject carrying realm `catalog-editor`, an `@OpaPreAuthorize`-gated catalog PUT,
  with a `ToggleableRoleSupplier` test bean (the `@TestConfiguration` bean-override idiom from
  `ResourceResolutionGateIT`). **I1** — supplier throws (outage) → **`403 ACCESS_DENIED` problem+json**,
  the row byte-identical (no version bump), and **`ProgrammableOpaClient.calls == 0`** (the outage
  denied without an OPA call → the realm fallback was never fed it). **I2** — same subject, supplier
  returns `Optional.empty()` (authoritative no-role) → the manager **reaches OPA** (here it grants) and
  the write applies — the designed fallback path, unbroken. Both PASS.
- **Docs reconciled (D1/D2):** the SPI javadoc (`RoleDefinitionSupplier`, T1) and the
  `HttpRoleDefinitionSupplier` class javadoc (T4) read true. [[ABAC-AUTHORIZATION]] gained the tri-state
  contract block (the mechanism note: supplier-classifies / consumer-maps, the strict 204-only
  invariant). [[PERMISSION-MODEL]] gained the "the realm fallback fires for an authoritative no-role
  only — never an outage" note. [[POC-ROADMAP]] B2 row + route note → **Shipped, next 6.7**.
  [[USER-STORIES]] gained **A4** (the operator security-posture story). The index [[B2-SUPPLIER-OUTAGE]]
  banner + frontmatter flipped to `status/done`; the ticket table ticked T1–T5.
- **Mulch:** three durable `opa-abac` patterns recorded (tri-state SPI classify-in-supplier/map-per-consumer;
  strict 204-only HTTP classification; the 5-consumer sweep) — `git restore --staged .` run **before**
  `ml sync` (the mulch commit touched only `.mulch/`, verified). The `autonomous-runs` retro is a
  phase-④ record (post-`/deep-review`), not part of this run.

## Tests

- **Headline IT:** `SupplierOutageGateIT` — I1 + I2, both PASS (real Postgres).
- **Whole suite (E1):** `./gradlew build` **green** (every module + both example services + all ITs).
  **`opa test infra/opa/policies/` → PASS: 157/157** (unchanged — zero Rego).
- **e2e through the live rig** (OIDC + user-service, **rebuilt B2 images**, `--pods 2`, in-network
  token): every `run-*.sh` matrix + `catalog-e2e` **green, 0 failures** —
  run-matrix 19, tag 12, team 11, filter 16, pagination 27, hierarchy 4, hierarchy-list 10+10,
  resource-resolution 12, permission-categories 27, catalog-e2e 19 = **167 assertions, 0 failures**.
  Additivity confirmed live: B2 changed no live behavior (no production supplier in the suite is under
  outage; the catalog pods run `role-source=http → usermgmt` and saw 204/200 as designed).

## Architecture review + refactor

**Nothing substantive to refactor at the slice gate.** The IT bean-override idiom (the one friction
point the planning flagged) was straightforward — `ResourceResolutionGateIT`'s `@TestConfiguration` +
static-flag test double pattern fit directly; the throwing supplier is a 4-line `ToggleableRoleSupplier`.
The IT asserts the *gate-boundary* contract (outage → no OPA call → 403; empty → OPA reached); the real
Rego realm-fallback grant is covered by the live newman suite + `opa test`. Whole-slice fail-closed,
security, and additivity were verified at each prior ticket's ★ gate; this gate confirmed they compose
(full build + 167 live assertions + 157/157 OPA, all green).

## Integration / e2e

The optional live **"stop the user service" outage cell** (E1 nice-to-have) was **DEFERRED — not a
silent cap.** Reason: the load-bearing proof of the cut is deterministic in `SupplierOutageGateIT`
(I1/I2), and live additivity is fully proven (167 newman assertions green on the rebuilt images). A
scripted stop/assert/restart of the `usermgmt` container proved unreliable in this run's tool context
(the `podman` CLI intermittently returns empty output when invoked from the shell here — the same
container the newman suite reaches fine via `podman run --rm`), so forcing it risked leaving the rig
half-down for no incremental assurance the IT doesn't already give. The rig integrity was re-verified
after the aborted attempt (`run-team-matrix.sh`, which exercises the live usermgmt resolve path, stayed
green 11/0). Recommend the live outage cell be re-attempted manually or in CI where the podman CLI is
stable; it is a demonstration, not a correctness gate.

## Decisions

None reopened. I2's IT shape was settled within the slice's intent: with a stubbed `OpaClient` the
faithful gate-boundary proof is "empty → OPA reached (grants); outage → OPA never reached (403)"; the
real Rego fallback grant is covered end-to-end by newman + `opa test`. No 503 / no new error code (I1 is
the uniform `403 ACCESS_DENIED`). Zero Rego (157/157).

## Commit

`test(e2e): SupplierOutageGateIT + docs reconcile + slice record (B2 shipped)` — branch
`feature/void3110/supplier-outage`. (Includes the `git mv` of the slice folder to `implemented/`.)
