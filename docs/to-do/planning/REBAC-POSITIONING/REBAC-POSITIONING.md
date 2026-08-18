---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/methodology
---

# ReBAC / Zanzibar positioning — a README section + a comparison note

> **Status: 📋 idea, not scheduled.** Opened 2026-08-18. Trigger: a maintainer conversation with an
> external LLM that classified this repo's example as a **ReBAC + ABAC hybrid**, not pure ABAC.
> **Nothing in that conversation is treated as fact here** — see *Verify before publishing*.

## The observation

The repo is called `spring-boot-starter-opa-abac` and brands itself ABAC throughout. But look at what
actually decides an access:

| mechanism | what it really is |
|---|---|
| **membership is the sole access path** to a catalog | a **relationship** predicate — user → team → resource |
| **N-level hierarchy**, an ancestor grant widening a descendant list | **graph traversal** (ltree ancestor walk) |
| the **supervised** path — a manager reaches a report's catalogs by *derivation* | a **relationship** two hops out (manager → report → team → resource) |
| tags + `ANY_OF`/`ALL_OF` matching | genuine **attributes** |
| permission categories + expansion | genuine **attributes** |
| the `env` production tier + `auth_time` freshness | genuine **attributes** (incl. environment) |

The **access spine is relational**; the attributes *refine* what the relationship already opened. That
is the shape ReBAC describes, and the docs never say so.

## Why this is worth doing (three reasons, in order of strength)

**1. Accuracy — and admitting it makes the docs stronger, not weaker.** A reader who knows Zanzibar
will spot the relationship spine immediately, and a project that names it first reads as more
competent than one that lets them notice. There is no downside to precision here.

**2. It is the discoverability lever the SEO research already pointed at.** `topic:abac+spring` is a
~9-repo niche. ReBAC / Zanzibar / OpenFGA / SpiceDB is a far larger space with real mindshare, and a
technically honest comparison is exactly the kind of *genuine* content the research found does work
(as opposed to keyword stuffing, which does nothing). Right now the README says **"ReBAC" zero
times**.

**3. The differentiator is sharp, defensible, and currently unstated.** Zanzibar-style engines answer
`Check(user, relation, object)` and `ListObjects(...)`. The latter hands back a **set of ids** that
the application must then push into its own query — the familiar giant-`IN`-clause / second-round-trip
problem once the set is large. This library takes a different route: OPA's Compile API returns a
**residual**, which becomes a **JPA `Specification` composed into the query itself**, so the database
does the filtering and the hierarchy is part of the predicate. *That* is the claim worth making, and
it is a claim about mechanism, not marketing.

## Where I would push back on the external analysis

The conversation dismissed the maintainer's own heuristic — *"relations **in** the policy = ReBAC;
relations arriving **as input** = ABAC"* — as "implementation style, not the model."

**I think the heuristic is more useful than that, and it should be the spine of the note.** The
textbook definition asks *what the decision is based on*; the heuristic asks *where the relationship
graph lives and who traverses it* — which is the architecturally load-bearing question:

- **Zanzibar/SpiceDB/OpenFGA:** the tuple store lives **inside** the authorization system, which owns
  the traversal, the consistency model (zookies/snapshot reads), and the reverse index that makes
  "who can see X" cheap.
- **This repo:** relationships are resolved **outside** Rego — the user-service resolves the effective
  role, the app resolves ancestors — and arrive as *input* to a policy that then reasons over them as
  attributes. There is no tuple store, no zookie, no reverse index.

So the honest one-liner is: **a relationship-shaped model evaluated through an attribute mechanism.**
Both halves matter. And note this is precisely what **Phase 8 (ReBAC-in-Rego)** already proposes to
test — moving the team-grant join *into* the policy — so the note and that experiment are the same
thread, and the note should forward-reference it rather than duplicate it.

## Deliverables (when scheduled)

1. **README — a short section**, ~1 short paragraph + the one-line claim. Names the hybrid honestly,
   states the partial-eval-vs-`ListObjects` difference, links to the note. Not a competitor teardown.
2. **`docs/architecture/REBAC-AND-ABAC.md`** — the detailed note: the model comparison, where each
   fits, what this repo does and does *not* provide (no tuple store, no zookie/consistency story, no
   native reverse index), and honest guidance on **when to pick a Zanzibar engine instead**.
   Example-dense, per the ReadMe.LLM finding that runnable examples lift LLM comprehension sharply.
3. A row in the `/deep-review` guide-selection matrix, if the note becomes a guide (an unmapped guide
   is write-only).

## Verify before publishing (non-negotiable)

The trigger conversation is **LLM output and is not a source.** Every competitor claim must be checked
against primary documentation before it goes in a public repo — the OpenFGA/SpiceDB communities are
technical and a wrong claim would cost more credibility than the note earns. Specifically verify:

- what `ListObjects` / `LookupResources` actually return, their limits, and their pagination story;
- whether any Zanzibar engine now offers SQL pushdown or a materialized reverse index that weakens
  the differentiator above;
- the consistency vocabulary (zookies / `at_least_as_fresh` / snapshot reads) before describing it;
- the existence and current state of any Spring/OPA data-filtering starters named as prior art.

**Write nothing comparative that has not been read from the other project's own docs.**

## Related

- **Phase 8 (ReBAC-in-Rego)** in [[POC-ROADMAP]] — the same thread as an experiment
- [[USER-STORIES]] — the "Future / comparison epic" note
- `07-Research/opa-abac-AI-Discoverability-and-SEO.md` (vault) — why comparison content is the lever
