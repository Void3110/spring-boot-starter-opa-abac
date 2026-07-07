---
tags:
  - status/done
  - type/project
  - area/security
  - area/api
---

# STATUS — T1: Port SPI: UserDirectory + DirectoryUser + NoOpUserDirectory (library)

**Status:** ✅ DONE

## What shipped

The reusable identity-search seam in `opa-abac-spring-security`, package
`dev.dmitriikonovalov.opaabac.security.directory` (all-new, additive):

- `UserDirectory` — `List<DirectoryUser> search(String query, int limit)`; the Javadoc pins the
  fail-closed-to-empty / never-throw contract, the blank-`q` no-call rule, the limit clamp semantics
  (≤0→20, >50→50), and the no-oracle property (ADR 0020 §1/§8).
- `DirectoryUser` — record `(subject, displayName)`, the type-bounded disclosure ceiling (§7).
- `NoOpUserDirectory` — `List.of()` for every query; the lean-starter `@ConditionalOnMissingBean`
  default T3 wires.

Consumers (named, arriving later per decomposition): T3's auto-config supplies the bean; T4's
endpoint injects it. No consumer in this ticket beyond the unit tests.

## Tests

`NoOpUserDirectoryTest` (module suite `:opa-abac-spring-security:test` — **green**):
- **U1a** — NoOp returns empty for `"anything"`, `""`, `null`, and a negative limit.
- **U1b** — `DirectoryUser.class.getRecordComponents()` is exactly `[subject, displayName]` — the
  disclosure ceiling asserted by reflection, so any widened field fails the build before it can leak
  through an endpoint.

## Architecture review + refactor

Nothing substantive — the ticket is three plain types mirroring `ResourceOwnershipResolver`'s shape
(interface + fail-closed default, Javadoc pinning the invariant, `spring-security` not core).
Verified explicitly:

- **Fail-closed/no-oracle:** NoOp is empty on every input (incl. `null` q, negative limit), never
  throws; the interface contract pins every edge for implementations.
- **Security:** the DTO ceiling is the record itself, regression-proofed by U1b's reflection assert.
- **Concurrency:** n/a — pure read seam, stateless final NoOp, no gated mutation in this slice.
- **Boundary:** `opa-abac-core` untouched; zero existing files modified (three new files + one test).
- **Layer separation:** no Spring or Keycloak types here — plain Java; wiring stays in the starter (T3).

One deliberate call: `DirectoryUser` has **no compact-constructor validation** — the never-throw
guarantee belongs to `search` implementations (which must catch-all and fall back
`displayName = subject`); a validating record could turn a mapper bug into a throw inside the search
path.

## Integration / e2e

n/a for T1 (pure library SPI + unit tests; first IT arrives with T2's HttpServer stub).

## Decisions

None beyond the pinned forks — no design gap surfaced.

## Commit

`feat(directory): add UserDirectory port SPI with type-bounded DirectoryUser and NoOp default`
