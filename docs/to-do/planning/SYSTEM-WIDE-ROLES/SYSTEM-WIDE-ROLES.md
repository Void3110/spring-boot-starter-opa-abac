---
tags:
  - status/planned
  - type/research
  - area/abac
  - area/opa
---

# System-wide roles — platform administrator & security auditor (industry research)

> **Status: 📋 researched, not scheduled.** Opened 2026-10-07. A landscape pass over how shipping
> platforms model roles that are **not** scoped to one team's catalog — a *platform administrator* and a
> *security auditor* — read against this repo's invariants. Nothing here is decided; §6 lists the forks a
> `grill-me` must settle before an ADR.
>
> **Method.** Primary vendor documentation was fetched directly. A second sweep used an external research
> assistant for gaps, 2025–2026 changes and missed platforms, and **every lead it returned was re-checked
> at the cited source before inclusion**. The few that could not be re-checked (the page would not render)
> are marked *(unverified)*.

## TL;DR

1. **The industry splits "system-wide" by *function*, not by *reach*.** A platform administrator runs the
   **control plane** (tenancy, teams, ownership, dictionaries, who-may-grant). A security auditor reads
   **authorization metadata** (who holds what, and the audit trail). Neither one reads business content
   by default.
2. **"The admin sees everything" is the legacy lineage, and its own vendors now fence it.** Azure keeps
   Entra admin roles separate from resource access. GitHub enterprise owners get no org content by default.
   Kubernetes documents `system:masters` as an irrevocable bypass to avoid. Salesforce's *View All Data* is
   the surviving example of the old pattern.
3. **When an admin does need content, the convergent move is an explicit, visible, logged *self-grant*
   through the normal grant path, not a bypass.** Examples: Azure "elevate access", GitHub "join the
   organization", Databricks ("no direct access by default … permission grants are audit-logged"),
   Atlassian ("add yourself to the `jira-administrators` group"), and Snowflake re-granting through
   `MANAGE GRANTS`. Where the operator is a *vendor* reaching into a *customer's* data, the bar rises to
   **customer approval**: Google Cloud Access Approval, and WorkOS impersonation (off by default, a
   mandatory reason, 60-minute sessions).
4. **Guardrails bind administrators too.** AWS SCPs restrict even a member account's root user, GCP deny
   policies apply "regardless of the IAM roles", and a Cedar `forbid` always overrides `permit`. Here, that
   means a platform role is evaluated **through** the policy, never as `allow if is_admin`.
5. **Assignments are few, elevated just in time, and step-up gated.** Examples: Entra (fewer than five
   Global Admins, PIM), GCP PAM, GitLab Admin Mode (re-auth, a 6-hour cap) and Snowflake (MFA on
   ACCOUNTADMIN). **ADR 0030's step-up machinery already gives this repo the GitLab-Admin-Mode shape for
   free.**
6. **Separation of duties: the admin cannot erase the trail, and the auditor cannot act.** See NIST
   AU-9(4) and AC-6(9)/(10), and Snowflake's SECURITYADMIN/SYSADMIN split.
7. **For this repo, the best fit is a third *disjoint* access path.** Platform and audit roles are resolved
   server-side from a stored, governed assignment and provenance-stamped (ADR 0031). Their `permissions`
   name **control-plane types only**, so catalog content stays closed *by construction*, which is the same
   trick ADR 0029 slice A used. The realm-role-claim shape is the fallback ADR 0018 removed.

## 1. Where the repo stands today

Grounded in the shipped ADRs, not assumed:

| Concern | Today's mechanism | Source |
|---|---|---|
| Access to catalog content | **Team membership is the sole path.** No coarse list gate, no realm-role fallback, and a non-member sees nothing | [[adr/0018-team-scoped-resource-isolation\|ADR 0018]] |
| Cross-team oversight | **Supervised subtree.** It is derived from the reporting relation, is read-only and step-up gated for production, and stays disjoint from membership. The ADR states there is "no god-mode, and no 'read all catalogs' capability anywhere in the design" | [[adr/0029-supervised-read-scope\|ADR 0029]] §2, [[adr/0030-step-up-decision-contract\|ADR 0030]] |
| Platform-level writes | **The operator, by network position plus seed.** System roles and GLOBAL tag keys are seeded and immutable (409). `/internal/bootstrap/*` is in-network and unreachable through the gateway. The `env` tag is `operatorManaged` and has no public write path | [[adr/0004-dynamic-tag-dictionary\|ADR 0004]], ADR 0019, ADR 0030 §3 |
| The one surviving realm-role grant | `catalog:create` only ("realm role = may onboard a catalog") | ADR 0018 §2 |
| The persona backlog | "**Platform / super-admin** *(future)*: … an unconditional grant that data-filtering must honor as 'see everything'" | [[USER-STORIES]] (persona table, D3) |

So the repo **already has a platform operator**, but it is an *infrastructure* identity (seed scripts,
in-network calls), not an authenticated, audited *human* role. The gap this research addresses is the
human one.

**The USER-STORIES persona and ADR 0029 already disagree.** D3 imagines an unconditional "see
everything" grant, and ADR 0029 rules that capability out. The industry evidence below sides with ADR
0029, so the persona text should be re-scoped whenever this is scheduled (see fork F1).

## 2. The survey

What each platform ships. "Admin sees content by default?" is the column that matters most.

| Platform | Platform-admin analogue | Admin sees content by default? | Auditor analogue | Guardrails bind admin? | JIT / step-up |
|---|---|---|---|---|---|
| **Microsoft Entra + Azure** | Global Administrator (directory) · Owner / User Access Administrator at the root scope `/` (resources) | **No.** "Microsoft Entra role assignments do not grant access to Azure resources." Access comes only through *elevate access*, a self-assignment that is logged, flagged with a banner, and should be removed after use | Global Reader · Security Reader | Deny assignments | PIM: eligible → activate, time-bound, approval, MFA |
| **AWS** | `AdministratorAccess` in a member account · the management account | Within the account, yes. But SCPs cap even member-account admins **including the root user**, and the management account is exempt (so keep it empty) | **`SecurityAudit`**: "read security configuration metadata", as distinct from `ReadOnlyAccess` | **Yes.** SCPs, explicit deny | IAM Identity Center + external tooling |
| **Google Cloud** | `organizationAdmin` and bindings at the org node, inherited down | Org-node bindings inherit, so yes if granted. The industry fence is **deny policies** | `iam.securityReviewer`: "Provides permissions to list all resources and allow policies on them". About 2,560 permissions, nearly all `*.list` / `*.getIamPolicy`, with **no** `storage.objects.get`. But it **does** include `storage.objects.list` (object *names*) and `logging.privateLogEntries.list` (it reads the audit trail itself) | **Yes.** Deny "regardless of the IAM roles they've been granted", with `exceptionPrincipals` | **PAM**: entitlements, max duration, approvals, logged |
| **Kubernetes** | `cluster-admin` via ClusterRoleBinding | Yes, and that is why the docs say "should not use `cluster-admin` accounts except where specifically needed" | Built-in `view` **excludes Secrets** (reading them yields service-account credentials, i.e. escalation) | `system:masters` is the anti-pattern: it "bypasses all RBAC rights checks … cannot be revoked" and skips authorization webhooks | Impersonation from a low-privilege account |
| **GitHub** | Enterprise owner · org owner | **No.** "Enterprise owners do not have access to organization settings or content by default, but they can gain access by joining any organization" | **Security manager** (org/enterprise): read on all repos plus write on security alerts | Rulesets | Sudo mode (re-auth) |
| **GitLab** | Instance Administrator | **No, when Admin Mode is off.** Admin access requires "Enter Admin Mode" plus re-auth, with a fixed 6-hour expiry | **Auditor user**: read-only on all groups/projects, no Admin area, no settings. **Custom admin roles** (`read_admin_users`, `read_admin_projects`, …; GA in 18.3) | — | Admin Mode |
| **Snowflake** | ACCOUNTADMIN, above SECURITYADMIN (grants), USERADMIN (users/roles) and SYSADMIN (objects) | **Not automatically.** Objects owned by a custom role outside the SYSADMIN hierarchy are not manageable without a re-grant via `MANAGE GRANTS` | `SNOWFLAKE` database usage/governance views | Ownership model | "At least two" ACCOUNTADMINs, MFA required, "should not be used to create objects" |
| **PostgreSQL** | Superuser (bypasses everything, including RLS) | Yes. That is the historical model the predefined roles carve down | `pg_monitor`, `pg_read_all_settings`, `pg_read_all_stats` (metadata) vs `pg_read_all_data` (content, which **still does not bypass RLS**) | RLS needs an explicit `BYPASSRLS` | — |
| **Elasticsearch** | `superuser` | Yes on data. But on restricted indices like `.security` it has **read-only** access only | `viewer` | Restricted system indices | — |
| **HashiCorp Vault** | Root token | Yes, which is why it is used only for "just enough initial setup … or in emergencies, and revoked immediately" | Policy-scoped read tokens | — | `generate-root` needs a **quorum** of unseal-key holders (break-glass, M-of-N) |
| **Salesforce** | *View All Data* / *Modify All Data* | **Yes**: these "override sharing settings for all objects". This is the god-mode lineage, still shipping | *View Setup and Configuration* | — | — |
| **Okta** | Super Admin · **custom admin role = role + resource set** | Scoped by the resource set | Read-only Admin | — | — |
| **Keycloak** | Master-realm admin ("use the master realm only to create and manage the realms") · `realm-admin` | Within the realm | `view-realm`, `view-users`, … (realm-management client roles) · FGAP v2 delegation to user/group subsets | — | Step-up flows (ACR) |
| **Databricks** | Account admin, above workspace admin (one workspace) and metastore admin (one Unity Catalog metastore) | **No.** Account admins "do not have access to the workspace by default, but they can grant themselves … the workspace admin role". For metastore admins: "There is no direct access by default. Permission grants are audit-logged." Data is reached by transferring ownership to oneself | — | — | — |
| **Atlassian** | Organization admin, above site admin. "Only organization admins can add or manage other organization admins" | **No** for product administration: "If you're an Organization admin or a Site admin, you'll need to add yourself to the `jira-administrators` group" | — | — | — |
| **Google Workspace** | Super admin | Broad: it administers every setting, and full access to all users' calendars is reported *(unverified)* | Custom read-only admin roles | — | "Give each super administrator 2 accounts … a separate account for daily activities"; more than one super admin, "each managed by a separate individual" |
| **WorkOS** | Dashboard team roles (Admin, Developer, Support, **Support Viewer**), kept separate from customer org roles | Only through **impersonation**, which is "not enabled by default", Admin-enabled, needs a mandatory reason, lasts 60 minutes, and records the impersonator and reason on `session.created` | Support Viewer (read-only, no impersonation) | — | Time-boxed sessions |

### What the authorization engines prescribe

- **OpenFGA** (modeling design principles) recommends a **dedicated `system` type** for internal/super-admin
  access: `type system { define admin: [user] }` and on the tenant `define admin: [user] or admin from
  system`. The stated reasons are that it "clearly separates your internal access from customer access",
  "avoids recursive relations", and "makes audit and compliance reviews easier".
- **Oso** ships a `global { roles = ["admin"] }` block. Its own example maps the global admin onto a
  *distinct* resource role (`"internal_admin" if global "admin"; "read" if "internal_admin"`) rather than
  onto the customer's `admin`. Even here, the global role becomes a separate, narrower role, not a
  superset.
- **Cedar**: "An explicit `Deny` for any one policy **always** overrides any `Allow`." An admin `permit`
  never beats a `forbid` guardrail.
- **OPA** has no global-admin guidance. Its FAQ ("How do I write policies securely?") documents the
  hybrid `authz if { allow; not deny }`, which "allow[s] relatively coarse grained parts of the request
  space and then carve[s] out of each part what should actually be denied". An admin rule written into
  `allow` therefore still loses to `deny`. A rule that bypasses deny is the author's choice, not a
  documented pattern. The common tutorial one-liner `allow if user_is_admin` becomes a bypass only when
  it sits on the entrypoint itself.
- **Permit.io, Cerbos, SpiceDB, Topaz**: no documented cross-tenant super-admin pattern was found. The
  absence is itself telling: Permit.io states "only users in a tenant can act on the resources in that
  tenant" *(unverified)*.

### Recent changes (Jan 2025 – Oct 2026)

The direction of travel is **more narrow, named, read-only and security-specific roles, and fewer
legacy all-powerful ones.**

- **Azure, May 2026:** classic administrator roles "are fully retired as of May 2026 and the Classic
  Administrators tab has been removed from the Azure portal". The last pre-RBAC all-powerful subscription
  roles are gone. Jan 2025: elevate-access entries arrive in the Entra directory audit log (preview).
  Feb 2025: a Sentinel detection for elevate-access events.
- **GitHub, 23 Oct 2025 (public preview):** an **Enterprise Security Manager** role, assigned through
  enterprise teams, which can "Manage alerts enterprise-wide" and "Manage security settings". It is a
  security function lifted to enterprise scope without owner rights.
- **GitLab 18.3:** custom admin roles GA (read-only Admin-area slices such as `read_admin_users`).
- *(unverified)* Several new reader-shaped Entra roles in 2026, and Snowflake phasing out `ORGADMIN` in
  multi-account orgs in favour of `GLOBALORGADMIN`.

## 3. Ten convergent patterns, and what each means here

**P1. Control plane ≠ data plane.** The platform admin administers *the platform* and does not
read tenant content by default. Evidence: Azure (Entra vs RBAC are "secured independently"), GitHub
enterprise owners, GitLab Admin Mode off by default, Snowflake ownership.
→ *Here:* a platform role's `permissions` name **only control-plane types** (`team`, tag definitions,
role templates, the audit surface) and no `catalog`/`category`/`product` key. Content is then closed **by
the role**, exactly as ADR 0029 slice A closed supervisor contents. ADR 0031's lesson applies: check that
no inheritance table hands child verbs to a non-membership role, and provenance confinement already
covers this.

**P2. Content access is an explicit, visible, logged self-grant through the normal path.** Azure
elevate access creates a real role assignment at `/` and logs it in the Entra audit log and the Activity
log, with portal banners and a Sentinel template. GitHub owners "join the organization". Databricks
account and metastore admins have "no direct access by default" and reach data by transferring ownership
to themselves, with "permission grants … audit-logged". Atlassian org/site admins "add yourself to the
`jira-administrators` group".
→ *Here:* if a platform admin needs content (orphan recovery, incident), they **add themselves to the
governing team** or **receive ownership** through the existing membership endpoints. That shows up on the
roster, passes every escalation gate, is revocable, and introduces **no new path into data**. This is the
cheapest design that preserves ADR 0018's invariant.

**P3. The binding sits at a root scope or on a singleton object, never as a bypass.** Examples: GCP org
node, Azure `/`, a K8s ClusterRoleBinding, OpenFGA's `system` type, Oso's `global`. The counter-example
is K8s `system:masters`, which is irrevocable, skips webhooks, and is documented as the thing to avoid.
→ *Here:* the realm-role-claim shape (`allow if "platform-admin" in input.subject.roles`) **is**
`system:masters`. It is token-carried, revocable only by expiry, invisible to the roster, and it is the
fallback ADR 0018 removed. A claim may remain a **UX eligibility marker** only, which is exactly ADR 0029
§6's treatment of `unit-supervisor`. The grant itself is a stored, governed assignment resolved
server-side and stamped with its own provenance value (ADR 0031: absence is closed).

**P4. Guardrails bind administrators too.** Examples: AWS SCPs, GCP deny policies (with explicit
`exceptionPrincipals`), Cedar `forbid`, Azure deny assignments, and Elasticsearch's read-only
`.security` for `superuser`.
→ *Here:* deny-overrides (`abac_deny`), `operatorManaged` keys and the owner-only fences
(`define-roles`, `transfer-ownership`) keep applying to platform roles. Any fence a platform admin *is*
allowed to cross must be named as an explicit exception, the way GCP names `exceptionPrincipals`. Orphan
recovery (`transfer-ownership` of an ownerless team) is the strongest candidate; GitHub sends orphaned
repos to Support for exactly this reason.

**P5. The auditor reads authorization *metadata*; content-reading auditors are the minority shape.**
The metadata shape is AWS `SecurityAudit` vs `ReadOnlyAccess`, Entra Global Reader, GCP Security
Reviewer, K8s `view` minus Secrets, Postgres `pg_monitor` vs `pg_read_all_data`, and GitLab custom admin
roles (`read_admin_*`). The content shape is the GitLab Auditor user and the GitHub security manager.
→ *Here:* two separable capabilities. **Authz-metadata read** covers teams, memberships, role
definitions (custom included), the tag dictionary, supervision edges, platform-role assignments and the
decision trail. **Content read** is a different and much heavier grant. The metadata auditor is also the
consumer of [[WHO-CAN-ACCESS]] use case B (the "why" and completeness view), so the two features may
want to be scheduled together.
**A caveat from GCP's Security Reviewer:** "metadata" still includes **names** (`storage.objects.list`)
and the **audit trail itself** (`logging.privateLogEntries.list`). Catalog and product names can be
sensitive, so whether the auditor may enumerate catalog names is a real fork, not a given (see §4).

**P6. Separation of duties: the admin cannot cover their tracks, and the auditor cannot act.** NIST
**AU-9(4)** limits audit-log management to a subset of privileged users. **AC-6(9)** logs privileged
functions. **AC-6(10)** prevents non-privileged users from executing them. Snowflake splits who grants
(SECURITYADMIN) from who owns objects (SYSADMIN), and Entra separates Privileged Role Administrator from
Global Administrator.
→ *Here:* platform-role grants are themselves logged and visible to the auditor, no role can grant itself,
and the auditor role carries **zero** write verbs (a fail-closed test, not a convention).

**P7. Standing access is the exception; elevation is just-in-time and step-up gated.** Examples: Entra PIM
(eligible → activate, time-boxed, approval, MFA), GCP PAM (entitlement, max duration, approvals, logged),
GitLab Admin Mode (re-auth, 6-hour cap), Snowflake MFA on ACCOUNTADMIN, and NIST AC-6(2) (use a
non-privileged account for non-security work).
→ *Here:* [[adr/0030-step-up-decision-contract|ADR 0030]] already ships resource-server freshness
(`acr` + `auth_time`, which defeats refresh laundering), the structured `deny_reason`, and the RFC 9470
challenge. Gating **every platform-role write on fresh elevation** is GitLab Admin Mode with no new
protocol. Time-boxed *activation* (PIM/PAM-style expiry on the assignment row) is a separate and larger
slice.

**P8. Few holders, plus a break-glass path that sits outside the normal one.** Entra recommends fewer
than five Global Admins, fewer than 10 privileged assignments, and two cloud-only emergency accounts.
Snowflake requires at least two ACCOUNTADMINs. Google Workspace wants more than one super admin, each with
a separate daily account. Vault's root token is for setup and emergencies only, regenerating it needs a
quorum, and it is the one identity that bypasses policy ("root tokens are not subject to Sentinel policy
checks"). The AWS management account is outside SCPs. **Bypass exists only for the break-glass identity.**
→ *Here:* the in-network operator plane (`/internal/bootstrap`, seed) **is** the break-glass path and
should stay one. The human platform role does not replace it. Who bootstraps the *first* platform admin
is an operator act, not an API call.

**P9. Read-audit is off by default, so privileged reads must be logged deliberately.** GCP: "Data Access
audit logs are disabled by default"; "Admin Activity audit logs are always written; you can't …
disable them".
→ *Here:* every decision taken on the platform/audit path should land in the decision trail as such
(OPA decision logs or an app-side audit row). An auditor who can read but leaves no trace is itself a
finding.

**P10. When the operator is not the data owner, content access requires the owner's approval.** Google
Cloud Access Approval: "Cloud Customer Care and engineering teams require your explicit approval
whenever they need to access your Customer Data". The exception is auto-approval for time-sensitive
outages, which is still "logged in an `auto approved` state". WorkOS impersonation is "not enabled by
default", is Admin-enabled, requires a recorded reason, "automatically expire[s] after 60 minutes", and
stamps the impersonator's email and reason on `session.created`. Salesforce *Grant Login Access* lets a
user grant admin login for a bounded period *(unverified)*.
→ *Here:* this is P2 with a second key. A platform admin's self-grant into a team could require the
**team owner's approval** (or an auto-approved, flagged emergency path). That is the natural answer if
F7 resolves to "the platform operator is not the tenant".

## 4. Candidate shapes for this repo

| Shape | What it is | Verdict |
|---|---|---|
| **A. Platform scope + stored bindings** | A singleton `platform` governing target (the OpenFGA `system` analogue). Platform/audit roles are ordinary `RoleDefinition`s whose `permissions` key only control-plane types, bound in the user-service, resolved server-side, provenance-stamped `platform` / `audit`, and entering policy as a **third disjoint path** | **Leading.** It reuses role≠grant, categories, deny-overrides, provenance and the step-up envelope, and adds no data path |
| **B. Realm-role claim → Rego** | `allow if "platform-admin" in input.subject.roles` | **Reject.** It is the ADR 0018 fallback / `system:masters`. Keep the claim as a UX eligibility marker only |
| **C. "Platform team" auto-member of every team** | An implicit membership everywhere | **Reject.** It over-grants, pollutes rosters, collides with `uq_team_target`, and was already rejected for the supervisor |
| **D. Self-grant for content** | No content in the platform role; content only via an explicit membership the admin gives themselves, step-up gated and logged | **Pair with A** as the content story (P2) |

**Draft capability split (for discussion, not decided):**

- **Platform administrator.** Team lifecycle (create/archive), ownership assignment and **orphan
  recovery**, unbinding a squatted catalog, curating GLOBAL tag definitions (seed-only today, 409),
  system role templates, and possibly `operatorManaged` values (but see the warning below).
- **Security auditor.** Read-only over teams, rosters, role definitions, the tag dictionary, supervision
  edges, platform-role assignments, the decision trail, and the who-can-access "why". No catalog content
  and no write verb of any kind.

> **⚠️ A dependency this would trip.** ADR 0030 §3: *"if `env` ever becomes writable at runtime, this
> default must flip to deny-until-tagged."* Untagged-defaults-to-non-production is safe **only** because no
> public path writes `env`. Giving a platform admin a runtime write on `operatorManaged` keys breaks that
> premise, so either keep `env` operator-plane-only or flip the default in the same slice.

**Invariants to restate in the ADR:**

- ADR 0029 §2 ("no god-mode, no read-all capability") **survives** under shape A, because the platform
  role has no content verbs. State that explicitly so it is not re-litigated.
- `GovernedScopeResolver` stays "never an unconditioned universe" **for catalogs**. An auditor listing
  *all teams* is a universe over a **different type**, so pin whether that is acceptable and whether
  catalog *metadata* (names only) is in or out.
- Precedence (ADR 0029 §5 extended): membership > supervision > platform, kept disjoint so that a row's
  provenance, and therefore its residual, is never ambiguous.
- The surviving `catalog:create` realm-role grant: does the platform role subsume it, or does it stay as
  the one claim-driven grant?
- Library vs example: like ADR 0029 slice A, start **example-side** and promote a "platform scope"
  concept to a published SPI only once a real consumer pins the contract.

## 5. Related

- [[adr/0018-team-scoped-resource-isolation|ADR 0018]]: the invariant every shape must keep
- [[adr/0029-supervised-read-scope|ADR 0029]]: the precedent for a disjoint non-membership path and a synthesized role
- [[adr/0030-step-up-decision-contract|ADR 0030]]: reusable elevation (P7), and the `env` dependency
- [[adr/0031-inheritance-confined-to-membership-roles|ADR 0031]]: the provenance stamp a new path must carry
- [[adr/0015-control-plane-vocabulary-categorization|ADR 0015]]: the CONTROL vocabulary and owner-only fences
- [[WHO-CAN-ACCESS]]: use case B is the auditor's main feature
- [[USER-STORIES]]: persona D3 to re-scope

## 6. Forks to settle (grill-me agenda)

| # | Fork | Options | Research leans |
|---|---|---|---|
| F1 | Platform admin and content | none + self-grant (D) · none + self-grant **with team-owner approval** (P10) · read-only content · full | **none + self-grant** (P1, P2); add owner approval if F7 says the operator ≠ tenant |
| F2 | Auditor scope | authz-metadata only · metadata + content (GitLab Auditor) | **metadata first** (P5); content only on a scoped, step-up, logged path |
| F3 | Source of a platform-role assignment | stored binding · IdP group/realm role · claim = eligibility + row = grant | **claim = eligibility, row = grant** (P3) |
| F4 | Who grants platform roles | operator plane only · platform admin (no self-grant) · a separate "security admin" (Snowflake split) | Open. SoD (P6) argues against a platform admin minting peers unchecked |
| F5 | Standing vs just-in-time | standing + step-up per act · time-boxed activation (PIM/PAM) | **standing + step-up first** (ADR 0030 reuse); JIT as a later slice |
| F6 | Fences the platform role may cross | none · named exceptions (orphan `transfer-ownership`, squat unbind) | **named exceptions only** (P4) |
| F7 | Tenancy | one deployment = one platform · multi-tenant (operator vs tenant admin: Okta resource sets, Entra admin units, GitHub enterprise vs org) | Pin the scope; the starter has no tenant concept today |
| F8 | Audit trail of record | OPA decision logs · app-side audit table · both | Open. P9 says it must exist; AU-9(4) says who may manage it |
| F9 | Library surface | example-only first · published SPI | **example-only first** (ADR 0029 precedent) |

## Sources

Primary vendor documentation, fetched 2026-10-07 unless marked.

- Microsoft: [Elevate access for a Global Administrator](https://learn.microsoft.com/en-us/azure/role-based-access-control/elevate-access-global-admin) ·
  [Best practices for Microsoft Entra roles](https://learn.microsoft.com/en-us/entra/identity/role-based-access-control/best-practices)
- AWS: [`SecurityAudit` managed policy](https://docs.aws.amazon.com/aws-managed-policy/latest/reference/SecurityAudit.html) ·
  [Service control policies](https://docs.aws.amazon.com/organizations/latest/userguide/orgs_manage_policies_scps.html)
- Google Cloud: [IAM deny policies](https://docs.cloud.google.com/iam/docs/deny-overview) ·
  [Privileged Access Manager](https://docs.cloud.google.com/iam/docs/pam-overview) ·
  [Cloud Audit Logs](https://docs.cloud.google.com/logging/docs/audit) ·
  [IAM roles: `iam.securityReviewer`](https://docs.cloud.google.com/iam/docs/roles-permissions/iam) ·
  [Access Approval](https://docs.cloud.google.com/assured-workloads/access-approval/docs/overview)
- Microsoft: [What's new in Azure RBAC](https://learn.microsoft.com/en-us/azure/role-based-access-control/whats-new)
- Databricks: [Admin concepts](https://docs.databricks.com/aws/en/admin/admin-concepts) ·
  [Unity Catalog admin privileges](https://docs.databricks.com/aws/en/data-governance/unity-catalog/manage-privileges/admin-privileges)
- Atlassian: [Jira global permissions](https://support.atlassian.com/jira-cloud-administration/docs/what-are-global-permissions-and-what-do-they-do/) ·
  [Admin role types](https://support.atlassian.com/user-management/docs/what-are-the-different-types-of-admin-roles/)
- Google Workspace: [Security best practices for administrator accounts](https://knowledge.workspace.google.com/admin/users/security-best-practices-for-administrator-accounts)
- WorkOS: [Impersonation](https://workos.com/docs/authkit/impersonation) ·
  [Dashboard members and roles](https://workos.com/docs/dashboard/members-and-roles)
- GitHub: [Enterprise teams and the Enterprise Security Manager (changelog, 2025-10-23)](https://github.blog/changelog/2025-10-23-managing-roles-and-governance-via-enterprise-teams-is-in-public-preview/)
- HashiCorp: [Vault Sentinel](https://developer.hashicorp.com/vault/docs/enterprise/sentinel)
- OPA: [FAQ: how do I write policies securely](https://openpolicyagent.org/docs/faq)
- *(unverified, page did not render)* Salesforce: [Grant login access](https://help.salesforce.com/s/articleView?id=xcloud.granting_login_access.htm&language=en&type=5) ·
  Permit.io: [Multi-tenant authorization](https://docs.permit.io/concepts/multi-tenant-authorization/)
- Kubernetes: [RBAC good practices](https://kubernetes.io/docs/concepts/security/rbac-good-practices/) ·
  [RBAC authorization](https://kubernetes.io/docs/reference/access-authn-authz/rbac/)
- GitHub: [Abilities of enterprise roles](https://docs.github.com/en/enterprise-cloud@latest/admin/managing-accounts-and-repositories/managing-roles-in-your-enterprise/abilities-of-roles) ·
  [Security managers](https://docs.github.com/en/organizations/managing-peoples-access-to-your-organization-with-roles/managing-security-managers-in-your-organization)
- GitLab: [Auditor users](https://docs.gitlab.com/administration/auditor_users/) ·
  [Admin Mode](https://docs.gitlab.com/administration/settings/sign_in_restrictions/) ·
  [Custom permissions (custom admin roles)](https://docs.gitlab.com/user/custom_roles/abilities)
- Snowflake: [Access control considerations](https://docs.snowflake.com/en/user-guide/security-access-control-considerations)
- PostgreSQL: [Predefined roles](https://www.postgresql.org/docs/current/predefined-roles.html)
- Elastic: [Built-in roles](https://www.elastic.co/docs/reference/elasticsearch/roles)
- HashiCorp: [Vault tokens: root tokens](https://developer.hashicorp.com/vault/docs/concepts/tokens)
- Salesforce: [Comparing security models](https://developer.salesforce.com/docs/atlas.en-us.securityImplGuide.meta/securityImplGuide/users_sharing_vs_obj_level_perms.htm)
- Okta: [About custom admin roles](https://help.okta.com/en-us/content/topics/security/custom-admin-role/about-creating-custom-admin-roles.htm)
- Keycloak: [Server administration: fine-grained admin permissions](https://www.keycloak.org/docs/latest/server_admin/index.html#_fine_grained_permissions)
- OpenFGA: [Modeling design principles](https://openfga.dev/docs/best-practices/modeling-design-principles) ·
  Oso: [Global roles](https://www.osohq.com/docs/modeling-in-polar/role-based-access-control-rbac/globalroles) ·
  Cedar: [Policy syntax: forbid vs permit](https://docs.cedarpolicy.com/policies/syntax-policy.html)
- NIST SP 800-53 r5: [AU-9(4)](https://csf.tools/reference/nist-sp-800-53/r5/au/au-9/au-9-4/) ·
  [AC-6 and enhancements](https://csf.tools/reference/nist-sp-800-53/r5/ac/ac-6/)
