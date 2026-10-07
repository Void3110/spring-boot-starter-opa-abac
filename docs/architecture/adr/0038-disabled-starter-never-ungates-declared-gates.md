---
tags:
  - status/active
  - type/decision
  - area/abac
  - area/spring-security
---

# ADR 0038 — Turning the starter off never silently ungates a declared `@OpaPreAuthorize`

**Status:** Accepted — implemented 2026-10-07; release 1.5.0.
**Date:** 2026-10-07
**Context tags:** fail-closed, master switch, `opa.abac.enabled`, present-but-inert gate, `@OpaPreAuthorize`, startup check

> `opa.abac.enabled=false` removed the `@OpaPreAuthorize` advisor along with every other starter bean,
> so each annotated method then ran with **no decision at all**: a fail-open caused by one configuration
> value, in code that still reads as secured. This ADR makes that state impossible to reach by
> accident. Settled with the maintainer on 2026-10-07. The finding surfaced on 2026-10-03, while
> planning the MCP-auth library extraction: a downstream adopter that ships `opa.abac.enabled=false`
> had to replace the annotation with explicit gate calls.

## Context

`OpaAbacAutoConfiguration` is gated as a whole on
`@ConditionalOnProperty(prefix = "opa.abac", name = "enabled", havingValue = "true", matchIfMissing = true)`.
Off, none of its beans exist, including the method-security advisor that binds
`OpaPreAuthorizeAuthorizationManager` to the annotation. The annotation is only metadata. With no
advisor, Spring proxies nothing and the method body just runs. There was no error, no log, and the
property was not documented. Verified on `4b6c474` with an `ApplicationContextRunner` test: with the
starter on, the call is denied (no subject). With it off, the same call returns the method's result.

Every other starter surface fails differently when the starter is off:

| Surface | With the starter off |
|---|---|
| `@OpaPreAuthorize` | **runs ungated** — the defect |
| `AbacFilter`, `OpaClient`, `AbacQueryService`, … (and whatever the app builds from them, such as the request-level `OpaAuthorizationManager`) | the bean is absent: an application that injects one **fails at startup**. An application that looks one up as optional made that choice in its own code (the example's unguarded profile does, on purpose) |
| `_actions` enrichment | absent; it is an affordance, never enforcement (ADR 0016) |

So the annotation is the one **declarative** surface that silently loses enforcement. That breaks the
repo's rule that a switch's off state is never wider than its on state. ADR 0014 §5 refuses a kill-switch
outright because its off position would be the vulnerability. ADR 0028 pins every agent-gate kill-switch as
never wider when off. `opa.abac.partial-eval.enabled=false` drops to a coarse path that keeps the
deny-override AND-ed.

The off state also has **legitimate** users, all in this repo, and each one runs annotated methods
without a decision **on purpose**:

1. **The ADR 0021 §2 load-test baseline.** `ENABLE_OPA=0` gives the catalog pods
   `OPA_ABAC_ENABLED=false`, so the library gate is the only variable between the two passes. A dedicated
   unguarded endpoint was rejected there.
2. **The bare rig without OIDC** (`ENABLE_OIDC=0`). There is no token to authorize, so the catalog runs
   with the starter off behind the gateway.
3. **The user-service's standalone default**, `enabled: ${OPA_ABAC_ENABLED:false}`. There is no OPA and no
   gateway, and nothing authenticates a bearer, so `/api/v1/**` is a 401 wall.

A fix that removes the off state, or makes it deny everything, breaks all three. The real defect is that
the off state was reachable **silently**.

## Decision

### 1. Off with a declared gate and no acknowledgment: startup fails

With `opa.abac.enabled=false` and any bean method carrying `@OpaPreAuthorize`, the context refuses to
start. It throws an `IllegalStateException` that names every gated method as `Type#method` and gives
both ways out: remove `opa.abac.enabled=false` to enforce the gates, or acknowledge them (§2). The check
runs after the eager singletons exist and before the web server starts, so the app never serves a request
in that state.

### 2. The acknowledgment: `opa.abac.allow-ungated-methods=true`

A boolean, default `false`, in the same shape as `opa.abac.subject.trust-forwarded-jwt`: the unsafe
posture needs a second, explicit key. When set, the context starts and a WARN names each method that runs
without a decision. It **has no effect while the starter is enabled**, so a deployment can set it next to
a flippable `enabled` (the rig does) without weakening the enabled mode. Off with no annotated methods
needs no acknowledgment and logs nothing.

### 3. Detection is the advisor's own pointcut

The pointcut is exposed as `OpaMethodSecurityConfiguration.opaPreAuthorizePointcut()`, and the advisor
now builds itself from it. The guard applies it the way the auto-proxy creator does
(`AopUtils.canApply`), so the guard and the advisor cannot disagree about which methods are gated. An
annotation declared on an interface method counts (`checkInherited`). An instantiated bean is judged by
its target class behind any proxy. A lazy, prototype or scoped bean is judged by its predicted type and
is never instantiated for the check.

The guard does **not** depend on `@EnableMethodSecurity`. The annotation declares the gate, and turning
the starter off is an explicit act, which is what the acknowledgment is for. (A starter that is **on**
without method security keeps the existing startup WARN. That check cannot tell whether any annotated
method exists, which is why it only warns.) The guard is active only where Spring Security and web are on
the classpath, the same condition as the advisor. Without them the advisor never exists, so turning the
starter off changes nothing.

### 4. Where it lives

`OpaAbacDisabledAutoConfiguration` is a separate auto-configuration, active exactly when
`opa.abac.enabled=false`. It cannot be nested in the main one, which is gated off whole. It reads the one
acknowledgment property instead of binding `OpaAbacProperties`, so a malformed value elsewhere under
`opa.abac` does not newly fail a boot that has the starter off. The property is still declared on
`OpaAbacProperties`, so the configuration metadata documents it.

### 5. The repo's own off-state users set the acknowledgment

`deploy.sh`'s unguarded branch (uses 1 and 2) sets `OPA_ABAC_ALLOW_UNGATED_METHODS=true` next to
`OPA_ABAC_ENABLED=false`. The user-service's `application.yml` defaults it to `true` (use 3).
`UnguardedBootIT` boots with both properties and also pins the other side: the same catalog app, off and
unacknowledged, refuses to start and names its real gates. The ADR 0021 measurement is unchanged: the
same gates are absent and only the startup path differs.

### 6. Release: 1.5.0

The change is source- and binary-compatible. It adds one public static method and one property. It
changes startup **behaviour** for an adopter that ships `opa.abac.enabled=false` with annotated methods:
that adopter was silently fail-open and now fails at startup until they choose to enforce the gates,
acknowledge them, or remove the annotations. That is the upgrade note in `CHANGELOG.md`.

## Considered options

| Option | Why not |
|---|---|
| **Keep the advisor registered when off, and deny** | A "disabled" starter that still intercepts calls. The app boots healthy and fails every gated call: a quieter signal than a startup failure, and one that bites only under `@EnableMethodSecurity`. The three in-repo uses need an acknowledgment anyway, so this is more machinery for a weaker result. |
| **Document `enabled=false` as the "authorization off" switch** | Leaves the fail-open in place. Documentation is not a control, and the downstream adopter shows how it gets missed. |
| **Fail with no escape hatch** | Breaks the ADR 0021 baseline, the no-OIDC rig and the user-service's standalone default. Running the gates off on purpose is legitimate. Doing it silently is the defect. |
| **WARN only** (the existing missing-`@EnableMethodSecurity` check) | That check warns because it cannot know whether any annotated method exists. Here the guard knows it does. |
| **A `BeanPostProcessor` that fails per bean** | Names one bean at a time, and fails a lazy bean only at first use (at runtime). The whole-context scan reports everything at once, at startup. |
| **An enum, `opa.abac.when-disabled: fail \| permit`** | Leaves room for a future `deny` mode, but reads more abstractly. The plain boolean matches `trust-forwarded-jwt`. |

## Consequences

Positive: a declared gate never stops deciding without someone saying so in configuration, and the
failure names every method involved. The repo's deliberate uses keep working with one extra,
self-describing key.

Negative: an adopter running `enabled=false` with annotated methods must act on upgrade (§6).

Not covered, deliberately:
- a starter that is **on** without `@EnableMethodSecurity` (the existing WARN);
- objects registered with `registerSingleton`, which no advisor ever applied to;
- an application's own optional lookup of a starter bean (its code, its choice);
- apps without Spring Security and web, which never had the advisor.

## Related

- [[0021-load-testing-methodology|ADR 0021]] — the unguarded baseline (§2), which now sets the acknowledgment
- [[0014-supplier-outage-error-distinct|ADR 0014]] — no kill-switch, because its off position would be the vulnerability
- [[0028-agent-tool-call-authorization|ADR 0028]] — kill-switches whose off state is never wider than on
- [[0006-three-layer-enforcement-model|ADR 0006]] — the method gate is enforcement layer 2
- [[ABAC-AUTHORIZATION]] — the master switch, in the adoption recipe
