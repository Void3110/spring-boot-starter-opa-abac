---
tags:
  - status/active
  - type/review
  - area/security
  - area/docs
---

# Security-review skill — a portable, adaptable whole-surface security harness

A **vendor-neutral template** for a full-surface, agent-driven security review: map the real attack
surface → fan out one auditor per **angle** → **adversarially refute every finding** → **live-probe the
survivors against the running system** → run a **cross-angle composition pass** → write a dated,
committed report. It is the generalized form of the `security-review` skill this project runs (which is
project-tuned and lives in `.claude/skills/`, **gitignored** — so this doc is how the method travels).

> **Companion to [`DEEP-REVIEW-TEMPLATE.md`](DEEP-REVIEW-TEMPLATE.md), and deliberately *not* the same
> skill.** `deep-review` is **diff-scoped** — it reviews one branch's change. A security posture is a
> **whole-surface** property: privilege escalation and cross-tenant leaks hide in the *interaction*
> between slices that were each reviewed clean in isolation. Two more things separate this harness from a
> review: it **maps the surface as one attack surface** (not a diff), and — the load-bearing difference —
> it **proves findings against the running system** instead of stopping at code-reading. Reach for
> `deep-review` on a branch; reach for this for a pre-publish baseline, a post-slice delta, or a
> privesc/attack-surface hunt.

> **Why a template and not the skill itself.** The skill is wired to this repo — its policy corpus, its
> control plane, its tenant model, its rig's token-minting recipe. The *method* underneath is portable.
> Everything project-specific below is marked **«FILL IN»**; copy this file into your own repo, replace
> the slots, and you have your own security-review skill or `/command`. The worked examples are an
> authorization library on Spring/OPA/APISIX because concrete beats abstract — swap them for your
> domain's real attack surface and its one load-bearing invariant.

> **Provenance.** The orchestration shapes — *fan-out*, *adversarial verification*, *cross-angle /
> completeness pass*, *match complexity to value* — are **Anthropic's** dynamic-workflows patterns
> ([docs](https://code.claude.com/docs/en/workflows), the
> "[a harness for every task](https://claude.com/blog/a-harness-for-every-task-dynamic-workflows-in-claude-code)"
> blog). This template applies them to a security audit; it does not invent them. The per-project
> knowledge store is **Mulch** ([Jaymin West](https://github.com/jayminwest/mulch)). See
> [`../../guides/AUTONOMOUS-IMPLEMENTATION-FLOW.md`](../../guides/AUTONOMOUS-IMPLEMENTATION-FLOW.md) §8 for how
> the pieces fit.

---

## The four ideas that make this worth more than "grep for secrets"

A single agent auditing a codebase has the same three weaknesses `deep-review` names — **misses things**
on a confident pass, **over-grades plausible-but-wrong findings**, and a refute-only pipeline **can only
shrink the finding set**. A security review adds a fourth: **code-reading cannot tell a real leak from a
paper tiger** — only the running system can. This harness counters all four structurally:

- **Independent angles (fan-out).** One auditor per *attack surface* — policy corpus, control-plane
  privesc, tenant isolation, fail-closed edges, gateway/deploy config, client-token handling, secrets,
  dependency posture — each blind to the others, each hunting one class of defect. Diversity surfaces
  what one pass overlooks. *Angles are surfaces, not files* — the same divergence hides across two files
  a per-file lens reviews separately.
- **Adversarial verification.** Every candidate finding goes to a *separate skeptic* prompted to
  **refute** it from source ("default to refuted unless a real attacker gains something the design intends
  to prevent — the guard may exist upstream: the gateway, another gate, a fail-closed default"). It
  survives only on re-confirmation. This is the noise filter.
- **Live probes — the ~20% a diff review can't do.** Every survivor that needs runtime proof is
  **actually attacked against the running system**: mint a token as identity X, send the request, record
  the raw status + body. A 403 / empty list / 401 where you expected a leak **refutes** the finding at
  runtime — the rig disposes of what code-reading merely proposed. This is the single feature that makes
  a security review more than a careful read, and the reason the skill is separate.
- **A cross-angle pass that widens.** After per-angle refutation and probing, one pass hunts what no
  single angle could generate: does a **control-plane grant** + a **tenant-isolation gap** + a
  **fail-closed edge** *compose* into an escalation or a leak that each angle, alone, rated benign? This
  is where a self-authorable role meets a list-vs-GET divergence and becomes a real exploit. Its
  candidates face the same refutation.

One rule about how the angle prompts are written, inherited from `deep-review`: **state the invariant
that generates the defect class, not one past incident's mechanics.** "Every OPA/supplier/scope error →
deny/empty, never wider" covers the class; "check the `catch` in `OpaClient`" gets satisfied literally by
the next fail-open edge. If you take nothing else, take these four ideas and that rule — the phases below
are scaffolding around them.

---

## Adapt-me slots (fill these in once, per project)

| Slot | What it is | Example (this repo) |
|------|-----------|---------------------|
| **«ATTACK SURFACE MAP»** | The enumerated surfaces the fan-out covers — this *is* the angle list | policy corpus · control-plane privesc · tenant isolation · fail-closed edges · gateway/deploy · client-UI tokens · secrets · deps |
| **«LOAD-BEARING INVARIANT»** | The one bug class that outranks everything; always Critical when violated | **fail-closed**: no path returns *more* access on error / missing input / timeout than on success |
| **«SECURITY INVARIANTS»** | The short list a finding must *violate* to be real (paraphrased into the primer) | role-level ceiling + senior-subset rule · membership is the sole access path · `/internal/**` never gateway-reachable · core stays framework-free · gateway bearer-only, never bypasses the policy engine |
| **«RIG BRING-UP»** | How to stand the system up with the *richest* demo data the privesc/isolation probes need | `ENABLE_SPA=1 ./deploy.sh up --pods 2` then `scripts/postman/seed-demo-data.sh` |
| **«PROBE RECIPE»** | The exact, verified way to mint a credential as a chosen identity and call the real entry point | mint in-network at `keycloak:8888` (issuer check), passwords==usernames, identities `editor/demo/viewer/outsider/alice/bob/carol`; call the gateway at `:9085` |
| **«RIG TRUTHS»** | The gotchas that read like bugs but aren't — the probe agent must know them | mint tokens *in-network*; restart the policy engine after a rule edit (`--watch` unreliable) |
| **«GUIDES / ADRs»** | The docs that define the invariants the review confirms hold | `docs/guides/PERMISSION-MODEL.md`, `TEAM-BASED-AUTHORIZATION.md`; ADRs behind the control plane + ownership gate |
| **«EXPERTISE STORE»** | The prime-before / record-after knowledge store + its domain | Mulch: `ml prime opa-abac` / `ml record … && ml sync` |
| **«CLEAN-ROOM / SECRET SCAN»** | The gate the (public) report must pass before commit | private blocklist in a gitignored `.local`, fail-closed; redact any live token in evidence to `<token>` |
| **«REPORT HOME»** | Where the dated report lands, on a branch | `docs/code-review/SECURITY-REVIEW-<date>.md` |
| **«BRANCH / IDENTITY»** | Branch naming + the commit identity to verify | `docs/security-review-<date>`; `git config --local user.email` is the personal one |

Everything below references these slots by name.

---

## Phase 0 — Load expertise + map the surface (main context, deterministic)

Before reading any code, prime the accumulated review knowledge and enumerate the surface, so the fan-out
starts with the project's hard-won lessons and covers the real attack surface, not a blank slate.

- **«EXPERTISE STORE»**: prime the security domain. *(Highest-leverage step — "an agent that knows this
  codebase's traps" vs "a clever stranger.")*
- Enumerate **«ATTACK SURFACE MAP»** from the real tree (list the policy files, the control-plane
  services, the gateway config) — the count is the fan-out's coverage claim; a surface you don't list is
  a surface no angle audits.
- Skim the **«GUIDES / ADRs»** as the security lens and distill **«SECURITY INVARIANTS»** into a tight
  primer prepended to every angle. **A finding is only real if it violates one of these** — that framing
  is what keeps the fan-out reporting leaks, not style.

## Phase 1 — Verify the system is probe-ready (main context)

Live probes are ~20% of the value and the thing `deep-review` can't do — so confirm the rig **before**
fanning out, or the probe phase fails wholesale on a dead system.

- Bring the rig up with **«RIG BRING-UP»** (the profile with the richest demo data — teams, custom roles,
  cross-tenant resources — that the privesc/isolation probes need).
- Prove probe-readiness end to end: the entry point rejects the anonymous call (bearer-only), a minted
  credential is accepted, an isolated identity is *rejected* where it should be. If any of these is
  wrong, fix the rig before the fan-out, not during it.
- **Demo-data hygiene — decide with the user.** Mutating probes (privesc / create attempts) dirty the
  seeded data downstream manual testing depends on. Two options: **(a) probe freely, re-seed after** (the
  seed is idempotent for the *base* demo, but hand-built state is LOST); **(b) snapshot the datastore and
  restore after** (preserves everything). Ask which; default (a) unless there's irreplaceable hand-built
  state.

## Phase 2 — The fan-out (route by value: *match complexity to value*)

| Scope | Path |
|-------|------|
| A single small diff on a branch | **Not this skill** — that's `deep-review`. |
| The policy corpus *alone* | The dedicated policy-audit workflow (this skill *calls that angle*, but here covers the whole surface). |
| Whole surface (pre-publish **baseline**) OR a risky slice + its interactions (**delta**) OR a named surface (**focus**) | **The multi-angle adversarial + live-probe workflow below.** |

Run it as a dynamic workflow (or, without that feature, as sequential sub-agents). **Baseline** audits the
whole current surface; **delta `<since-ref>`** tells every auditor to focus on what changed since the ref
*and anything that interacts with it* (a pre-existing issue the delta didn't touch is one-line-noted, not
deep-dived); **focus `<steer>`** narrows to a named surface. The shape:

1. **Prime** — one agent produces the tight invariants-and-hot-spots briefing prepended to every angle.
2. **Fan-out** — one **auditor per angle** from **«ATTACK SURFACE MAP»**, in parallel, each blind to the
   others, each hunting one defect class, each citing `file:line`. An empty findings array is a valid
   result — **do not invent findings.** Every finding carries a concrete *attacker path* (steps → what
   they gain) and a `needsProbe` flag with the exact identity/path/body to try.
3. **Adversarial verification** — hand **every** finding to a *separate skeptic* prompted to refute it
   from source; default to refuted unless a real attacker gains something the design intends to prevent.
   Severity may be *adjusted* here, not just confirmed/killed. Survivors only.
4. **Live probe** — for every survivor with `needsProbe`, an agent **actually attacks the running system**
   with the **«PROBE RECIPE»** (and knows the **«RIG TRUTHS»**): mint as identity X, send the request,
   record raw status + body. **Confirm only if the observed response proves the escalation/leak**; a
   block *refutes* it at runtime — say so plainly. Use *different* identities to prove a boundary.
5. **Cross-angle pass** — given all survivors (with probe results), hunt the *interactions* no single
   angle generates (control-plane grant × isolation gap × fail-closed edge → a chained exploit);
   reconcile contradictions; flag any finding the probes downgraded. New composite findings name a
   concrete chained attack.
6. **Synthesis** — write the dated report to **«REPORT HOME»**: exec summary (counts by severity + the
   single most important thing), a findings table (severity | angle | title | `file:line` | probe
   CONFIRMED/REFUTED/UNVERIFIED/n-a), per-finding detail (attack, verdict reasoning, probe evidence +
   **reproducible command**, and a *fix direction* — not the fix), the cross-angle section, a **"Refuted
   at runtime"** section (paper tigers the probes killed — *not* listed as open issues), a **"Deferred"**
   note (git-history secret scan, full CVE audit, supply-chain — for the publish/delta pass), and a
   severity-ordered **follow-up-branch** triage list.

> A reference implementation of this exact shape (prime → fan-out → verify → probe → cross → report) ships
> next to the project skill as `security-review-workflow.js` (structured-output schemas for findings /
> verdicts / probes; probe recipe handed verbatim to the probe agents). It is **gitignored** with the
> rest of `.claude/skills/`; this template is the committed description so the method is reproducible
> without it.

**Then spot-verify every Critical yourself** — read the cited files and *re-run the probe command*. The
harness cuts false positives; it does not replace your judgement on a publish-gating Critical, and you may
hold context (from the **«EXPERTISE STORE»** or the change's history) the agents lack.

## Phase 3 — Post-run (main context)

1. **Restore the rig** per the Phase-1 decision (re-seed, or restore the snapshot) so downstream manual
   testing has a working demo. Say what was lost if option (a).
2. **Read the report**; sanity-check the "Refuted at runtime" section — the probes should have killed
   paper tigers, not a *real* one you have extra context on. Confirm **no Critical rests on an unrun
   probe** (a `needsProbe` finding never probed is **UNVERIFIED**, not an open Critical — and the report
   must say so).
3. **«CLEAN-ROOM / SECRET SCAN»** on the report (it's committed, often public): the scan must come back
   empty, and any live token a probe pasted into the evidence must be redacted to `<token>`.
4. **Commit the report only**, on a branch, with **«BRANCH / IDENTITY»** verified. Do **not** push / open
   a PR / touch the main branch — the maintainer does that. **Fixes are a separate follow-up branch**,
   reviewed as a normal change.
5. **Record to the «EXPERTISE STORE»** any durable finding *class* — a recurring privesc shape, a
   fail-open pattern, a rig gotcha the probes hit — so the next pass starts ahead. If the store shares the
   repo, make the sync commit touch only the store (unstage the report first — the swept-staged trap).

---

## What to keep when you adapt this

The phases are negotiable; **these are not** — they're what make the harness better than a careful read,
and what make it a *different* skill from `deep-review`:

- **Angles are surfaces, verified, then probed.** Fan-out for coverage, skeptics for precision, **live
  probes for ground truth** (the diff-review can't do this), the cross-angle pass for the composite no
  single angle generates. Skip the probe and you have a slower `deep-review`, not a security review.
- **A finding must be probed or refuted, not asserted.** Code-reading proposes; the running system
  disposes. `needsProbe` + never-probed ⇒ **UNVERIFIED**, and the report says so.
- **Fail-closed is the bar.** The one class that outranks everything: a path returning *more* access on
  error / missing input than on success. Always Critical. Trace *every* failure branch to its safe
  terminal.
- **Whole surface, because leaks live in the seams.** Privesc and cross-tenant leaks hide in the
  *interaction* of slices each reviewed clean alone — the cross-angle pass is not optional.
- **Report-only; fixes are a separate, reviewed branch.** An unattended agent editing an authorization
  path is exactly the risk this review exists to catch. The report is the deliverable, committed and
  studyable; the fix is a follow-up task.
- **Clean-room + secret hygiene on the report itself.** It ships public — the scan must be empty and every
  live token redacted.
- **Prime before, record after.** The store is what compounds security-review quality across passes.

## Related

- [`DEEP-REVIEW-TEMPLATE.md`](DEEP-REVIEW-TEMPLATE.md) — the diff-scoped review counterpart, same
  instantiation model; this skill covers the whole surface and adds live probes.
- [`../../guides/AUTONOMOUS-IMPLEMENTATION-FLOW.md`](../../guides/AUTONOMOUS-IMPLEMENTATION-FLOW.md) §8 — the
  full tooling stack (Mulch / grill-me / deep-review / dynamic workflows) and upstream credits.
- [`DECOMPOSE-SKILL-TEMPLATE.md`](DECOMPOSE-SKILL-TEMPLATE.md) ·
  [`AUTONOMOUS-IMPLEMENT-SKILL-TEMPLATE.md`](AUTONOMOUS-IMPLEMENT-SKILL-TEMPLATE.md) —
  the phase-② / phase-③ template siblings.
- `CODE-REVIEW-WORKFLOW.md` · `CODE-REVIEW-CHECKLIST.md` — this project's concrete (non-templated) review
  lifecycle + per-finding checklist.
- Anthropic dynamic workflows — [docs](https://code.claude.com/docs/en/workflows) ·
  [harness blog](https://claude.com/blog/a-harness-for-every-task-dynamic-workflows-in-claude-code).
