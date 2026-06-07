---
tags:
  - status/active
  - type/review
  - area/docs
---

# Code Review

The review workflow for this repo. The `/deep-review` skill (local, in `.claude/skills/` —
**gitignored**, so it isn't in this repo) drives the full lifecycle; this folder holds the authoritative
process, checklist, and a portable template it references.

> **Adaptation status.** The `deep-review` skill began as a port of a mature review process and has
> since been **tuned to this repo** — it now speaks this project's modules (`opa-abac-*`), build
> (`./gradlew build`), e2e (the newman suites), and the fail-closed authorization invariant. Its heavy
> path is a multi-lens **adversarial** workflow (fan-out → refute → synthesize). The remaining
> generalization work is to lift the project-specific parts out into the portable template below; each
> shipped slice refines this. The **process** here is canonical.

## Documents

- [`CODE-REVIEW-WORKFLOW.md`](CODE-REVIEW-WORKFLOW.md) — the phases a review goes through (this project).
- [`CODE-REVIEW-CHECKLIST.md`](CODE-REVIEW-CHECKLIST.md) — per-finding checklist (this project).
- [`DEEP-REVIEW-TEMPLATE.md`](DEEP-REVIEW-TEMPLATE.md) — the **vendor-neutral, adaptable** version of the
  review harness, with project-specific parts marked as fill-in slots (for adopting this flow elsewhere;
  credits the Anthropic dynamic-workflows patterns it builds on).

## Mulch-driven

Reviews are **expertise-driven**: before reviewing, prime the relevant Mulch domain
(`ml prime code-review-process`, `ml search "<topic>"`). After a review surfaces a durable
insight, record it (`ml record … && ml sync`). See [`../../CLAUDE.md`](../../CLAUDE.md) and the
[mulch skill](../../.claude/skills/mulch/SKILL.md).
