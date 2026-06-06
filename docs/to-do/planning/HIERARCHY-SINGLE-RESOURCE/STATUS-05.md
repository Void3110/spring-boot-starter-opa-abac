---
tags:
  - status/done
  - type/project
  - area/abac
  - area/autoconfigure
---

# STATUS T5 — starter: wire resolver SPI + inheritance config (default-off) + `maxDepth`

> Filled in at the T5 checkpoint during the autonomous run. One commit per ticket.

## What shipped

The starter auto-config for hierarchy — **opt-in, default-off**, conditional + overridable:

- **`OpaAbacProperties.Hierarchy`** group: `enabled` (default **false**), `resolver` (`ltree`|`cte`, default
  `ltree`), `maxDepth` (default 32), and the structural **`inheritable`** map (`childType → [ancestorType…]`,
  default empty — mirrors the `inheritable[childType][ancestorType]` OPA data the rego clause reads, exposed
  so an app keeps one source of truth). Regenerated `spring-configuration-metadata.json` (4 hierarchy props).
- **`HierarchyAutoConfiguration`** (nested in `OpaAbacAutoConfiguration`): gated on
  `opa.abac.hierarchy.enabled=true` **AND** Spring Data JPA on the classpath. It wires:
  - the `AncestorResolver` — `LtreeAncestorResolver` (`resolver=ltree`, default) when the app supplies a
    `LtreePathSource` bean, or `RecursiveCteAncestorResolver` (`resolver=cte`) when it supplies a
    `ParentLinkSource` — `@ConditionalOnBean(source)` because the library can't know the app's tables;
  - the `HierarchicalAuthorizer`, `@ConditionalOnBean(AncestorResolver.class)`.
  All `@ConditionalOnMissingBean`, so an app-supplied resolver/authorizer overrides everything.

## Tests

`:opa-abac-spring-boot-starter:test` **green** (new T5 cases + all pre-existing); `ApplicationContextRunner`:
- **default-off** — no hierarchy beans without `hierarchy.enabled=true`, even with a source bean present;
- **enabled + `LtreePathSource`** → an `LtreeAncestorResolver` + the `HierarchicalAuthorizer`;
- **`resolver=cte` + `ParentLinkSource`** → a `RecursiveCteAncestorResolver`;
- **enabled but no source bean** → no resolver (the app must supply the data-access seam);
- an app-supplied `AncestorResolver` **overrides** the auto one;
- property binding: `maxDepth=8`, `resolver=cte`, and the `inheritable` map
  (`category=[catalog]`, `product=[category,catalog]`);
- defaults: off / ltree / 32 / empty inheritable.

## Architecture review + refactor (the ★ gate)

**Nothing substantive to refactor** — the wiring mirrors `DataFilteringAutoConfiguration`'s conditional
idioms. Verified:
- **default-off:** gated on `hierarchy.enabled=true` with **no** `matchIfMissing` (absent config ⇒ no beans;
  proven by the test).
- **partial-eval untouched:** the diff removes nothing from the `partialEval`/`abacQueryService`/`residual`
  beans or properties — hierarchy is purely additive.
- **app owns the data-access seam:** `@ConditionalOnBean(LtreePathSource/ParentLinkSource)` — no source ⇒ no
  resolver; **overridable** via `@ConditionalOnMissingBean`.

## Integration / e2e

Starter wiring is unit-verified via `ApplicationContextRunner` (no DB needed). The wired beans run against
real infra in T6/T7.

## Decisions

- **The resolver is `@ConditionalOnBean(source)`, not unconditional.** The library cannot know an app's
  table mapping, so it wires a resolver only once the app provides the matching `LtreePathSource` /
  `ParentLinkSource`. This keeps "enable hierarchy" a two-part opt-in (flip `enabled` + provide a source),
  and lets an app drop in a fully custom `AncestorResolver` instead.
- **`inheritable` lives in config but is consumed by the rego (OPA data), not the Java wiring.** The starter
  only uses `enabled`/`resolver`/`maxDepth` to build beans; `inheritable` is surfaced so the app can publish
  one declaration to OPA.

## Commit

`feat(starter): wire hierarchy resolver SPI + config (opt-in, default-off)` — see the T5 commit on
`feature/void3110/hierarchy-single-resource`.
