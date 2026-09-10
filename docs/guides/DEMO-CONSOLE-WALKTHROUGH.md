---
tags:
  - status/active
  - type/guide
  - area/demo-ui
  - area/abac
  - area/keycloak
---

# Demo console walkthrough — run the rig, then watch the backend decide

The example rig ships a small React console (`example-demo-ui/`) whose only job is to make the
library's decisions **visible**: the same OPA answers that gate the REST API are rendered as buttons
that appear, lock, or answer `403` honestly, and as rows that are simply not there. This guide is for a
developer meeting the repo for the first time: it takes you through running the rig, then walks the
console as four personas — **owner**, **member**, **outsider**, **supervisor** — and for every screen
explains **what just happened on the backend** and which decision record pins it.

> The console is demo scaffolding, not a production SPA — read the token-handling caveat in
> [`example-demo-ui/README.md`](../../example-demo-ui/README.md) before borrowing from it. The walk
> below was driven adversarially as a QA pass on 2026-09-10 ([[PRE-HABR-UI-QA-2026-09-10]]); every
> claim here has a wire-level observation there.

## Part 1 — Run the local rig

### 1.1 What you need

| You need | Why |
|---|---|
| Docker Desktop or podman, ~4 GB free for the rig | thirteen containers: gateway, identity, policy engine, two app services (two pods each), three Postgres, tracing, the console |
| Node.js 20+ and `npm` on the host | `deploy.sh` builds the console bundle host-side once and serves it through the gateway (`ENABLE_SPA=0` skips it) |
| **No local Java** for the rig | the app images build *inside* Docker; Java is only needed for `./gradlew build` |
| ~10 minutes for the first `up` | three Gradle builds inside Docker; later `up`s reuse the images (`./deploy.sh build` rebuilds the catalog image after a code change) |

### 1.2 Bring it up and seed it

```bash
./profile.sh up                     # base Postgres
./deploy.sh up --pods 2             # everything in the table below; ENABLE_MCP=1 adds the agent tool surface
scripts/postman/seed-demo-data.sh   # the demo team, roles, catalogs, tags — once per fresh rig
./deploy.sh status                  # containers + the gateway's pod pool
```

| Container | Port | What it does in a decision |
|---|---|---|
| `opa-abac-apisix` (+ `etcd`) | **9085** | the gateway: validates the bearer token's signature, expiry and issuer, then proxies with the token attached; the console lives at this origin |
| `opa-abac-keycloak` | 28888 (`admin`/`admin`) | the realm `catalog-demo` with every persona; also proxied *through* the gateway so the console's PKCE login is single-origin |
| `opa-abac-opa` | 28181 | the decision engine — every `allow`, every list filter, every `_actions` map |
| `catalog-1`, `catalog-2` | 28081/28082 | the catalog service (the app the starter secures), two pods round-robined by the gateway |
| `usermgmt`, `usermgmt-2` (+ Postgres) | 28090/28092 | the user-management service: teams, memberships, role definitions, the tag dictionary, the identity directory, and the org relation the supervisor path derives from |
| `opa-abac-jaeger` | 26686 | traces across gateway → service → user-service → OPA |
| `opa-abac-spa` | (via 9085) | nginx serving the built console bundle |

The seed creates the world the personas act in:

- **Demo team** governing the **Demo catalog** (categories **EMEA region** `region:emea` and **APAC
  region** `region:apac`, two products under EMEA), with a role ladder: `owner`, `administrator`,
  `senior`, `member`, `reader` (system roles) and the team's own `demo-admin`, `demo-editor`,
  `demo-viewer`, `alice-role` (custom roles — a custom role can never manage the team, ADR 0015 §5).
- The personas' bindings: `editor` → **owner**, `demo` → `demo-editor` (READ/WRITE/TAG), `viewer` →
  `demo-viewer` (READ), `outsider` → nothing.
- The **supervised pair**: `pm-demo` owns two more teams and their catalogs — **Demo Production
  Catalog** (`env=production`) and **Demo Open Catalog** — while `sup-demo` supervises `pm-demo` and
  is a member of nothing.
- The tag dictionary: global keys `region` (emea | amer | apac, multi), `sensitivity` (public |
  internal | confidential), `env` (operator-managed).

The seed is idempotent; re-run it after a `./deploy.sh down -v`.

### 1.3 Sign in

Open <http://localhost:9085>. The card lists every persona and the rule *password = username*.
"Sign in with Keycloak" sends you to the realm's login form **at the same origin** — the gateway
proxies `/realms/*`, and Keycloak rewrites the URLs it advertises to that origin, so authority, issuer
and redirect agree without `/etc/hosts` entries or CORS ([`infra/README.md`](../../infra/README.md)).
The console keeps the tokens in `sessionStorage` and sends the access token as a Bearer on every call.

## Part 2 — The path of one request

Every click below becomes one or more HTTPS calls that take the same road. Knowing it once makes each
persona's screen self-explanatory:

```
browser ──Bearer JWT──▶ APISIX :9085
                          │ openid-connect: signature + expiry against the realm's JWKS; a Lua guard checks `iss`
                          ▼
                     catalog service (one of two pods)
                          │ 1. AbacSubjectExtractor reads the forwarded JWT (trust-forwarded-jwt: the gateway already verified it)
                          │ 2. resolve the resource: which catalog governs it (the "governing root"), its tags, its ancestors
                          │ 3. GET usermgmt /internal/effective-role?subject&targetType=catalog&targetId=<root>
                          │       → the ROLE DEFINITION this subject holds on that root (membership), or none
                          │ 4. build the OPA input and POST it to OPA /v1/data/<type>
                          ▼
                     OPA: {"subject":{id,roles,attributes}, "action":"catalog:view",
                           "resource":{type,id,attributes:<tags>,ancestors,root_attributes},
                           "role_definition":{permissions,required_tags,match_mode,denied_actions,attributes}}
                          │ allow := verb ∈ effective_actions(role_definition, type) ∧ tags_satisfied ∧ ¬denied
                          ▼
                     200 with the body … or 403 application/problem+json (or 401 + a challenge, Part 3.4)
```

Three variations you will meet:

- **Lists** do not ask "may I see row X?" per row. The service asks OPA to **partially evaluate** the
  same policy with the resource unknown (`POST /v1/compile`), turns the residual into a JPA
  `Specification`, and lets Postgres return only the rows the policy would allow — with the
  membership-governed catalogs as the base scope. The `count` in `{count, page, perPage, items}` is
  therefore *your* count ([[PARTIAL-EVALUATION-FILTERING]], ADRs 0005, 0010, 0012).
- **`_actions`** on each row is one **batch** OPA call (`/v1/data/<type>/bulk`) asking the four verbs
  for the rows just listed; the console renders the map, never guesses it. A row whose batch failed
  simply carries no `_actions` — omit, never fabricate (ADR 0016, [[ACTION-ENRICHMENT]]).
- **Team management** (add member, change a role, define a tag key) goes to the **user-service**,
  which authorizes with the same starter and its own policy (`team.rego`): the control-plane verbs are
  granted only to *system* roles on the ladder ([[PERMISSION-MODEL]], ADR 0015).

## Part 3 — Four personas, screen by screen

Each step: **Do** → **See** → **On the backend** → **Why** (the record that pins it).

### 3.1 The owner — `editor`

**Do:** sign in as `editor`.

**See:** the grid shows one card, "Demo catalog", and a usable "+ New catalog". Top right: the identity
chip and two **realm roles**, `catalog-editor` and `catalog-viewer`. The caption on the grid says
what those roles are worth here: exactly *one* power — `catalog-editor` may create catalogs.

**On the backend:** the list call went through Part 2's road with the resource unknown. The service
asked the user-service which catalogs this subject's **memberships** govern (`/internal/governed-targets`)
and used that id set as the base scope of the partial-evaluation filter — so the realm roles played
no part in *which* rows came back. The response is `{count:1}` with `_actions` and
`_provenance:"member"` already on the row.

**Why:** membership is the sole access path (ADR 0018, Slice B4): a realm role never grants a
catalog; being on a team that governs it does. The one exception is deliberate — `catalog:create` is
type-level (no team can exist before the catalog does), so it stays a realm-role check.

**Do:** open the catalog.

**See:** three panels. **YOUR ACTIONS ON THIS CATALOG**: ✓ view ✓ update ✓ delete ✓ assign-tags.
**TEAM**: the owner's control plane — per-member role dropdowns, transfer ownership, remove,
"+ Add member", ROLES (9), TAG KEYS (3). **CATEGORIES**: EMEA and APAC, each with view / update /
delete / assign-tags and "products →".

**On the backend:** the single `GET /catalogs/{id}` resolved the governing root (the catalog itself),
fetched `/internal/effective-role` (→ `owner`), asked OPA `catalog:view` (allow), then batched the
four verbs for the `_actions` map. The categories list ran the partial-eval filter under the same
role; the team panel is the user-service answering `GET /teams?targetType=catalog&targetId=…`, its
members, role definitions and tag definitions — each read authorized by `team.rego`.

**Why:** the `_actions` map is the affordance contract (ADR 0016): the UI shows what the server
decided, on this resource, for this subject. The control plane is visible because `owner` is a
**system** role; a custom role, however permissive, never gets `team:*` verbs (ADR 0015 §5).

**Do:** "+ New category", name it, pick `region = apac`, create. Then open its "assign-tags".

**See:** a `201`, the row appears carrying `region:apac`. The tag editor is dictionary-driven: a
`sensitivity` select, `region` toggles, and `env` shown as **operator-managed** — not settable here.

**On the backend:** the create was a **type-level** decision (`category:create` with no id) — the
role was resolved on the parent catalog and the verb checked against its WRITE grant; the tags in the
payload were validated against the **dictionary** (an illegal value answers `422 TAG_VALUE_ILLEGAL`
naming the allowed ones). `env` is refused on the write path because it is operator-managed: the
production tier must not be self-assigned (ADR 0030 §3).

**Why:** the dictionary is data, defined at runtime per team ([[TAG-BASED-AUTHORIZATION]] layers 1–2,
ADR 0004); validation is fail-closed so a typo can never widen a match.

### 3.2 The member — `viewer` (and `demo`)

**Do:** "Switch identity" (the session is cleared; Keycloak re-prompts, no silent single sign-on), sign
in as `viewer`.

**See:** the same grid, but "+ New catalog" is **amber** with the tooltip *"Your realm roles lack
catalog-editor — creating will answer 403. Left usable on purpose…"*. Open the catalog: ✓ view
✕ update ✕ delete ✕ assign-tags, the tag editor locked, the same three categories with view-only
buttons.

**On the backend:** identical calls, one different answer from `/internal/effective-role`:
`demo-viewer`, whose permissions are `{catalog:[READ], category:[READ], product:[READ]}`. OPA expanded
READ to `view`/`list` through the permission-category table and answered `false` for every mutation
verb in the batch — that `false` is what the console renders as a lock ([[PERMISSION-MODEL]], ADR 0007).
The **amber** button is different in kind: it is the *client* predicting from the realm roles in the
token; nothing on the server was asked yet.

**Why:** amber is a prediction, red is a verdict, and neither is enforcement. The console leaves the
control usable so that a wrong prediction cannot hide a right answer; the server is the only
authority (ADR 0016).

**Do:** in the team panel, change `demo`'s role in the dropdown.

**See:** it snaps back and the panel says *"✕ change-role → member failed: 403 — Access denied"*.

**On the backend:** `PUT /teams/{id}/members/{user}` reached the user-service, which asked OPA
`team:change-role` under `demo-viewer` — a custom role, so no control-plane verb — and answered
`403 application/problem+json` with `errorCode:"ACCESS_DENIED"`. The escalation ladder would have
denied even a system `member` promoting someone above themselves.

**Why:** controls are offered, denials are answered, nothing is silently hidden; and every denial is
RFC 7807 with a typed code ([[REST-API-DESIGN]], ADR 0011).

> `demo` (`demo-editor`, READ/WRITE/TAG) sits between the two: every write and tag affordance is
> present, every team-management control still answers 403 — write access never implies management.

### 3.3 The outsider — `outsider`

**Do:** sign in as `outsider`.

**See:** an **empty** grid, "+ New catalog" amber. Paste the Demo catalog's id into a direct `GET`
(devtools) and the answer is `403`, no shell, no children.

**On the backend:** `/internal/governed-targets` returned no catalogs, so the partial-eval filter's
base scope was empty and the list was `{count:0}` before OPA saw a row. The single `GET` resolved the
root, asked `/internal/effective-role`, got **no role definition**, and OPA denied — there is no
"subject roles" fallback for reads.

**Why:** the list cut and the single-resource gate must agree, or a deep link would leak what the
list hides (the 7.0.5 list↔GET agreement invariant, ADR 0018).

### 3.4 Tags as a grant — the member with a requirement

This is where the console shows attribute-based access rather than role-based. The requirement lives
**on the role**, not on the resource (ADR 0009): a role may declare `requiredTags`, and may then act
on a resource only when the resource's tags match (ANY_OF or ALL_OF). A role without a requirement is
never tag-gated — the owner cannot lock themselves out by tagging things.

**Do:** as `editor`, give `viewer` the seeded `alice-role` (READ/WRITE/TAG, requiring `region ∈ [apac]`
**and** `sensitivity ∈ [public]`). Sign in as `viewer` and open the catalog.

**See:** the catalog opens, but CATEGORIES says *"No categories visible to you in this catalog."*

**On the backend:** `/internal/effective-role` now returned `alice-role` **with** `required_tags` and
`match_mode: ALL_OF`. The partial-eval residual therefore carried the tag conjunct, and Postgres
returned nothing: EMEA fails on `region`, APAC has no `sensitivity`, and an **untagged** row counts as
non-matching. The catalog itself still opened because reads of the *governing root* are exempt from
the requirement by default (`data.config.root_read_tag_exemption`; `ROOT_READ_TAG_EXEMPTION=0
./deploy.sh up` makes them gate too).

**Do:** as `editor`, tag APAC `sensitivity = public`; look again as `viewer`.

**See:** exactly **APAC** is back, with all four buttons; EMEA is still gone and a direct link to it is
`403`.

**On the backend:** the same residual now matches one row; the `_actions` batch under `alice-role`
answered `true` for update/delete/assign-tags because the role holds WRITE and TAG *and* the tags
match. The direct `GET` of EMEA ran the same `tags_satisfied` and denied.

**Why:** fail-closed by construction — a role that narrows by attribute can only *lose* rows when a
requirement is added, never gain them ([[TAG-BASED-AUTHORIZATION]] layer 3, ADRs 0009 and 0022).

> **Known gap (DEF-1, planned):** the same tag-requiring role can still *create* categories and
> products it would not be allowed to read, because the type-level `create` gate checks the verb but
> not the tags. Tracked in `docs/to-do/planning/TAG-GATED-CREATE/`; not an escalation beyond the
> role's own WRITE.

### 3.5 The supervisor — `sup-demo`, with `pm-demo` as the control

Two personas, one production catalog ([[SUPERVISED-READ-AND-STEP-UP]], ADRs 0029–0033).

**Do:** sign in as `pm-demo` first.

**See:** two catalogs, "Demo Production Catalog" with a plain `production` badge and "Demo Open
Catalog". Open the production one: the category renders, no panel, no chip.

**On the backend:** `pm-demo` is a **member** (owner) of both teams, so the road is Part 2's: membership
role, OPA allow, `_actions` all true, `_provenance:"member"`. The production tier is irrelevant to a
member — no elevation is ever asked of them.

**Do:** sign in as `sup-demo`.

**See:** the same two catalogs, each with a **supervised** badge and view-only actions; the production
card also carries the amber **"production · verify to open"**. Open the **Open** catalog: its
category renders, nothing else. Open the **Production** catalog: the contents area is a **locked
panel** — 🔒 *"Production contents — fresh second factor required"*, the server's own sentence,
`acr_values aal2`, `max_age 300s`, and one [Verify]. The header chip reads **not elevated**;
breadcrumbs and the metadata card stay usable.

**On the backend:** `sup-demo` is a member of nothing, so `/internal/governed-targets` returned no
catalogs — but the service also asked `/internal/supervised-targets`, which the user-service derives
per request: her **transitive reports** (`pm-demo`) → the teams where those reports hold a
CONTROL-capable role → the catalogs those teams govern. Membership always wins, so the derived set is
`supervised = S \ M`. The list is a **two-leg** query over both id sets, and each supervised row is
labelled `_provenance:"supervised"`. On the single `GET`, `/internal/effective-role` took its
non-membership branch and **synthesized a read-only role** whose `attributes.provenance` is
`supervised` — that is why every mutation is false, and why the row inheritance that members enjoy is
closed for her (ADR 0031: inheritance is confined to membership-derived roles). Then the production
catalog's categories: the service fetched the governing root's tags into `root_attributes`
(`{"env":"production"}`, ADR 0032), and the policy required, for a supervised read under a production
root, `loa ≥ 2` **and** `now − auth_time ≤ max_age + skew` from the token — her token says `acr=aal1`,
so OPA answered a deny with `deny_reason: step_up_required`, and the library's problem advice mapped
*that* deny — only that one — to **`401`** with an RFC 9470 challenge:
`WWW-Authenticate: Bearer error="insufficient_user_authentication", … acr_values="aal2", max_age="300"`
(ADR 0030). Plain denials stay `403`.

**Why:** oversight is a second, disjoint access path that is read-only and scoped to the unit, and
production detail is gated on **freshness of the second factor**, decided on the resource server from
the token's `acr` and `auth_time` — not on "logged in with 2FA this morning" (ADRs 0029, 0030).

**Do:** click [Verify]. Keycloak asks for the **password, then a one-time code**; the code:

```bash
python3 scripts/postman/mint-code-flow-token.py --print-otp --otp-secret spachallengedemo1234
```

(the seeded secret is raw bytes on purpose, so a phone authenticator cannot enrol it — scriptable, not
phishable).

**See:** you land **back on the same catalog**; the panel is gone, the category renders, the chip counts
down **Elevated · 4:59**, and the card's amber "verify to open" is gone.

**On the backend:** [Verify] sent Keycloak `acr_values=aal2`, `max_age=0` and an *essential* `acr`
claim, so the realm's level-2 flow ran both factors and minted a token with `acr=aal2` and a fresh
`auth_time`. The console's callback replayed the one read that was refused; OPA's freshness check now
passed; the service answered `200` and emitted the audit event for a privileged production read. The
chip's window is the `max_age` the challenge advertised — there is no `300` in the console's code —
and when it reaches zero the chip turns amber and **nothing is hidden**: the next read decides, because
the policy's `skew` may still admit it. Refreshing the access token does **not** extend the window:
`auth_time` survives a refresh unchanged.

**Why:** the client never pre-empts the server; a stale window is a *prediction* (amber), a refused
read is the *verdict* (the panel). And no automatic redirect ever fires — a refusal after a
verification renders a passive panel, so the round trip is structurally loop-free (ADR 0033 and the
console's README).

## Part 4 — Reading the wire

Open devtools once; everything above is checkable there:

- every denial is `application/problem+json` with a typed `errorCode` and a UTC `timestamp`;
- lists are `{count, page, perPage, items}` with `_actions` per item and `_provenance` on catalogs;
- the only `401`s are the step-up challenge and an expired session — a legitimate path never bounces;
- `X-Upstream-Addr` alternates between the two catalog pods: the session is the JWT, nothing sticks.

## Part 5 — Resetting and opting out

```bash
./deploy.sh down            # stop everything, keep the data
./deploy.sh down -v         # wipe the volumes too; then up + seed for a fresh world
ENABLE_SPA=0 ./deploy.sh up # the rig without the console (no Node needed)
ENABLE_DIRECTORY=0 ./deploy.sh up   # no identity directory: the add-member search answers empty by contract
ENABLE_MCP=1 ./deploy.sh up # add the agent tool surface — [[AGENT-TOOL-AUTHORIZATION]]
```

## Part 6 — Have an agent do it, or make it yours

Two prompt files in [`docs/prompts/`](../prompts/README.md): [[RUN-THE-DEMO]] hands the whole walk
above to a coding agent and has it report the cut it observed; [[FROM-EXAMPLE-TO-YOUR-APP]] is the
recipe for reshaping the example into your own application — the shape, where every noun lives, the
order of work, and a prompt per step.

## Part 7 — What the console deliberately does not show

The deterministic proofs live elsewhere: the newman matrices under `scripts/postman/`
([[E2E-TESTING]]) assert every cut above through the gateway; `opa test` covers the policies; the
MCP surface has its own guide. And the console's token handling is a demo shortcut — see the
security caveat in its README before reusing any of it.
