---
tags:
  - status/in-progress
  - type/research
  - area/opa
  - area/keycloak
  - area/abac
---

# Why OPA, when Keycloak already does authorization?

> **Working note for the article series** — accumulating arguments, not yet prose. The angle:
> teams that run Keycloak for authentication see "Authorization Services" in the admin console
> and reasonably ask why they should add another moving part. This note collects the honest
> answer: what OPA actually buys, what it costs, and the boundary where each side wins.

## The one-paragraph answer

Keycloak authorizes over what the **IdP** knows — users, roles, groups, realm attributes, and
resources you *register into Keycloak*. Real application authorization is mostly about what the
**application** knows — the resource's owner, tags, status, position in a hierarchy, its
relationships — state that lives in the app's database and changes with every request. OPA is a
general-purpose decision engine that evaluates **whatever input you hand it** against **policy
kept as versioned, testable code**, locally, in microseconds. If your rules are "role X may call
endpoint Y," Keycloak (or plain Spring Security) is enough and OPA is overkill. The moment rules
depend on resource state — or you need *lists filtered by policy* — the IdP model runs out, and
that is precisely where OPA starts paying rent.

## What Keycloak Authorization Services actually gives you

Be fair to it — it is more than role checks:

- A **resource/scope/permission/policy model** managed centrally in the admin console, per
  client: registered resources with attributes, scopes on them, permissions tying policies to
  resources.
- **Policy types** over identity data: role, group, user, client, time, aggregate (JS policies
  exist but are deprecated/locked down — scripts must ship as deployed artifacts, which already
  concedes the "policy as code" point).
- **UMA 2.0** flows — user-managed access, permission tickets, RPTs: a *standardized protocol*
  for users sharing their resources with other users. This is genuinely hard to rebuild and OPA
  does not give it to you.
- Decisions via the **token endpoint** (RPTs carrying permissions) or policy-enforcer libraries —
  i.e., a **network round trip to the Keycloak server** (or an RPT cached with a staleness
  window).

## The bonuses OPA brings

Each of these is something this repo demonstrates live — the article can point at running code.

1. **Decisions on application state, not identity state.** Keycloak can only decide over data
   that has been pushed into it; deciding on a resource's current tags/owner/hierarchy means
   *registering and synchronizing resources into the IdP* — a shadow copy of your domain model,
   with all the drift that implies. OPA has no model of its own: the app builds the input
   document from the **real resource at decision time** (here: the gate resolves the instance
   and its ancestor chain before asking — [[ATTRIBUTE-RICH-PRE-AUTHORIZATION]]). The IdP stays
   the authority on *who you are*; the app stays the authority on *what things are*.

2. **Policy as code, with a real test harness.** Rego lives in the repo
   (`infra/opa/policies/`), goes through code review, runs `opa test` with coverage in the
   build, and is unit-tested against fixture inputs exactly like any other module. Policy
   changes are diffs in a PR, not clicks in an admin console that exist only in a realm export.
   (Expanded in its own section below — it changes the day-to-day workflow enough to deserve
   one.)

3. **Partial evaluation → data filtering. The feature with no Keycloak equivalent at all.**
   "May this user see *this* row?" generalizes to "*which* rows may this user see?" — OPA can
   partially evaluate a policy against a half-known input and hand back a **residual condition**
   the app compiles into SQL ([[PARTIAL-EVALUATION-FILTERING]]). One policy decides the single
   GET, the batch check, *and* the list filter. With an IdP-side authorizer the alternatives are
   N+1 permission calls per page or a hand-written parallel filter that silently diverges from
   the policy. For any app with "show me my stuff" list endpoints, this alone decides the
   argument.

4. **Local, sub-millisecond decisions.** OPA runs as a sidecar next to the service: no per-
   request round trip to the IdP, no RPT caching/staleness trade-off, no authorization outage
   when the IdP is briefly unreachable (and when OPA itself is unreachable, the client library
   **fails closed** — deny, never default-allow).

5. **Expressiveness where real models live.** Set algebra over categories that expand through a
   `data` table (this repo's `READ`/`WRITE`/`TAG`/`GRANT` model), deny-overrides, ancestor-chain
   inheritance with leaf-level veto tags, tag-match modes (ANY_OF/ALL_OF), delegation rules like
   "assignable iff the candidate's effective permissions are a subset of yours" — all of these
   are a few lines of Rego over the input document. None of them map onto role/group/time policy
   types.

6. **One engine at every enforcement layer.** The same policy language serves the gateway
   (coarse route checks), the application gate (`@OpaPreAuthorize`), batch decisions, and the
   data filter — the three-layer model (ADR 0006). Decisions are also **IdP-agnostic**: swap or
   add token issuers, do service-to-service authorization, even decide non-HTTP questions, with
   the same policies.

7. **Operability of decisions.** Decision logs (every allow/deny with its full input), dry-run
   "what-if" evaluation, bundle-based distribution — authorization becomes observable and
   replayable instead of a black box inside the IdP.

## Policy as code, in practice

Worth its own section, because "policy as code" sounds like a slogan until you see what the
workflow actually changes day to day:

- **A policy change is a pull request.** The rule, its tests, and the feature that needed it
  land in one reviewable diff. The reviewer sees *exactly* what widened or narrowed — there is
  no "someone changed a permission in the admin console last Tuesday" class of incident, and
  `git blame` answers *why* a rule exists.
- **Policies have a test suite.** `opa test` runs fixture inputs against every rule with
  coverage — including the cells that matter most and that console-configured authorization
  never gets: the *deny* cases. This repo's suites pin default-deny explicitly, assert that an
  unknown token grants nothing, and even verify partial-evaluation residual shape with
  `opa eval --partial` — authorization regressions fail the build, not the audit.
- **Review discipline becomes possible.** A checklist ("default deny is explicit; no
  unconditional `allow`; input is validated; deny paths are tested") is only enforceable on
  text in a diff. You cannot meaningfully code-review a tree of console-created policy objects.
- **Reproducibility and promotion.** The policy at commit `X` *is* the policy running in every
  environment built from `X` — dev/stage/prod drift disappears, and rolling back authorization
  is `git revert`, not console archaeology. Keycloak realm exports get you partway, but an
  export is *configuration you diff after the fact*, not a programming model you test before
  merging.
- **The decision data is code-shaped too.** The category→action expansion table here is a JSON
  document under `data` — the vocabulary itself is versioned, diffable, and parity-tested
  against the application's validation table, in the same PR as the rules that consume it.

The upstream signal pointing the same direction: Keycloak deprecated console-uploaded JS
policies and requires scripts to ship as deployed artifacts — i.e., even the IdP's answer to
complex policy is "put it in your build." OPA simply starts there.

## What it costs (the honest column)

- **Another moving part**: deploy, monitor, distribute policies/data to it, restart or reload on
  policy change. Not free, even as a sidecar.
- **Input-building is your job.** OPA knows nothing until you feed it; the extraction, resource
  resolution, and role-resolution machinery is real engineering effort — it is, frankly, most of
  what this repo *is*. With Keycloak you get the enforcement plumbing (for its model) off the
  shelf.
- **Authorization data has to come from somewhere.** Roles/grants/team membership need a home
  and a resolve path (this repo's user-service exists for exactly that).
- **Rego is a new language** with a learning curve and its own footguns (which is why the
  policies here ship with a test suite and a security checklist).
- **No management UI and no UMA.** If end users must share their resources with each other
  through a standard consent flow, Keycloak's UMA support is a real differentiator OPA lacks.

## When Keycloak (or less) is enough — OPA is overkill

The practical test: **write your five most complex authorization rules as sentences.** If every
one fits the form *"a user with role/group/claim X may do Y"*, you do not need a policy engine.

- Coarse endpoint gating by realm/client roles — token claims + `hasRole` already do it.
- A small, static permission set over identity attributes only.
- The thing you actually need is **user-to-user resource sharing** — that's UMA; Keycloak has
  the protocol, OPA doesn't.
- A single small service, no list-filtering requirements, a team without the capacity to operate
  one more component.

In those cases OPA adds an input-plumbing tax and a second policy store for rules the token
already answers.

## When OPA is actually required

Any one of these is a strong signal; two or more settle it:

- Decisions depend on **resource state or relationships** — ownership, tags, hierarchy, tenant,
  status, time-of-state (ABAC/ReBAC territory).
- **List endpoints must return only what the caller may see**, decided by the same policy as the
  single-resource check, at the database level (partial evaluation).
- Policy must be **versioned, reviewed, tested, and shared** across services, languages, or
  enforcement layers.
- **Latency or availability budgets** rule out a per-request IdP round trip.
- The same authorization must hold across **multiple token issuers** or non-HTTP entry points.
- Delegation/administration rules are themselves policy ("who may grant what") — the kind of
  set-algebra this repo's category + assignable model needs.

## The architecture that wins: they compose

The framing for the article is **not** OPA *versus* Keycloak — it's each component doing the job
it's the authority on (the shape this repo runs end-to-end):

| Layer | Component | Decides |
|---|---|---|
| Authentication + identity | Keycloak | who you are; coarse realm roles in the token |
| Edge | Gateway (APISIX) + OIDC | is the token valid; coarse route access |
| Application gate | OPA (`@OpaPreAuthorize`) | may *this subject* do *this action* on *this resource's real state* |
| Data access | OPA partial eval → JPA `Specification` | *which rows* the subject may see |

Keycloak stays — for what it's actually for. OPA takes over where the IdP's knowledge ends.

## Threads to pull for the article (TODO)

- [ ] A concrete worked contrast: the same rule ("members may edit categories tagged with their
  region, unless the category is deny-listed") expressed as Keycloak resources/policies vs ~10
  lines of Rego + input — let the reader feel the modeling mismatch.
- [ ] Numbers for the latency claim: local OPA decision vs a token-endpoint round trip (measure
  on the example rig).
- [ ] The fail-closed posture as a *library* responsibility (what "deny on any error" means in
  practice — worth its own section or sidebar).
- [ ] A fair paragraph on alternatives in the same niche (other policy engines / authz-as-a-
  service) so the article reads as a decision guide, not advocacy.
- [ ] Where the line moves over time: Keycloak features evolve — re-verify the Authorization
  Services capabilities against the current release before publishing.

## Related

[[ABAC-AUTHORIZATION]] · [[PARTIAL-EVALUATION-FILTERING]] · [[ATTRIBUTE-RICH-PRE-AUTHORIZATION]]
· [[TEAM-BASED-AUTHORIZATION]] · ADR [[0006-three-layer-enforcement-model|0006]] · ADR
[[0007-coarse-grained-permission-categories|0007]] (the category/delegation model the
"expressiveness" argument leans on)
