---
tags:
  - status/active
  - type/index
  - area/methodology
---

# AI Engineering Methodology

How this repo is built with AI agents: a deliberately **engineered loop** — `plan → decompose →
autonomous-implement → review` — where each pass leaves artifacts (in **Mulch** and the **Obsidian vault**)
that make the next pass better. The *way* this library gets built is itself a deliverable: a doc-first,
plan → autonomous-implement → test → review method that stays high-quality while being as autonomous as the
human-gated boundary allows.

> **This repo is the reference implementation.** The method was developed and hardened here across ~20
> slices, then generalized for other contexts. It stands on upstream work — **Mulch** (Jaymin West),
> **grill-me** (Matt Pocock), and **Anthropic's dynamic-workflows / "a harness for every task"** patterns —
> credited throughout.

## Notes

| Note | What |
|---|---|
| [`AI-ENGINEERING-METHODOLOGY.md`](AI-ENGINEERING-METHODOLOGY.md) | **Start here.** The method framed as an **engineered loop**: the two nested loops (inner ★review-gate self-correction · outer retrospective→planning), the two accumulators (Mulch = experience · the vault = decisions), the 4-phase lifecycle as one iteration, the three failure modes each structural element counters, the tooling stack, and the honest human-gated boundary. |
| [`../guides/AUTONOMOUS-IMPLEMENTATION-FLOW.md`](../guides/AUTONOMOUS-IMPLEMENTATION-FLOW.md) | **The deep dive + source of truth.** The full method with the *verbatim* prompt skeleton, the per-slice slot table, and the lessons baked into each. The skills defer to it (when a template and it disagree, it wins). Kept in `guides/` because the `.claude/skills/` reference it by path. |
| [`templates/DECOMPOSE-SKILL-TEMPLATE.md`](templates/DECOMPOSE-SKILL-TEMPLATE.md) | Phase ② — turn a settled design into the decomposition package (portable, vendor-neutral). |
| [`templates/AUTONOMOUS-IMPLEMENT-SKILL-TEMPLATE.md`](templates/AUTONOMOUS-IMPLEMENT-SKILL-TEMPLATE.md) | Phase ③ — the runner discipline around the per-ticket loop (resume, escalation, sub-agents-for-scouting-only). |
| [`templates/DEEP-REVIEW-TEMPLATE.md`](templates/DEEP-REVIEW-TEMPLATE.md) | Phase ④ — the portable adversarial review harness (diff-scoped): fan-out → refute → completeness-critic. |
| [`templates/SECURITY-REVIEW-SKILL-TEMPLATE.md`](templates/SECURITY-REVIEW-SKILL-TEMPLATE.md) | Phase ④ (security) — the *whole-surface* harness: fan-out over the attack surface, adversarially verified + **live-probed** against the running rig, dated report (report-only). |

## The method in one breath

```
① PLAN (interactive, grill-me)   →  ADRs + user stories + 00-DESIGN
② DECOMPOSE (docs-only)          →  ordered tickets + QA cases + the verbatim autonomous prompt
③ AUTONOMOUS IMPLEMENT (1 agent) →  per-ticket loop: prime → build → test → ★review → e2e → commit
④ REVIEW / SHIP (maintainer)     →  deep-review → push → PR → merge → run retrospective → Mulch
                                                                            └── closes the outer loop ──┐
①' the NEXT slice's planning primes `autonomous-runs` + reads the vault  ◀──────────────────────────────┘
```

Phases ①② front-load the thinking into **immutable artifacts** (ADRs) and an **unambiguous work list**;
phase ③ turns that into a *self-contained, checkpoint-gated, fail-closed* hand-off one agent executes end to
end; phase ④ ships it **and records what the run taught** — which is what the next slice's phase ① reads.
The ★ architecture-review gate and the per-ticket checkpoint catch mistakes before they compound; the two
accumulators make the learning compound instead of evaporate.

## How this fits the repo

- **ADRs** produced in phase ① live in [`../architecture/adr/`](../architecture/adr/README.md).
- **Decomposition packages** live in [`../to-do/`](../to-do/) (`planning/` → `implemented/` on ship).
- **Review notes** land in [`../code-review/`](../code-review/).
- **Mulch** is this repo's project expertise store (`.mulch/`) — primed before a task (`ml prime <domain>`),
  recorded after (`ml record … && ml sync`). The **`autonomous-runs`** domain is the outer loop's memory
  (one retrospective per slice run); the cross-cutting **`opa-abac`** domain holds the technical insights.
- **The `.claude/skills/`** (`decompose`, `deep-review`, plus the global `grill-me`, `mulch`, `rego-skill`)
  are the automation; they are gitignored and defer to
  [`../guides/AUTONOMOUS-IMPLEMENTATION-FLOW.md`](../guides/AUTONOMOUS-IMPLEMENTATION-FLOW.md).

## The honest boundary

The outer loop is **human-gated**: the maintainer runs `/decompose`, kicks off each run, does phase ④, and
decides what merges — *a loop with a human closing it each turn*, not a self-improving flywheel that ships
unattended. That auditability is the point for a portfolio artifact; a more-autonomous "software factory"
variant is a tracked future direction, not what this method claims today.

## Related

- Reference implementation: **this repo** — the public, clean-room worked case study these templates were
  distilled from.
- [[POC-ROADMAP]] — the slices this method has shipped.
