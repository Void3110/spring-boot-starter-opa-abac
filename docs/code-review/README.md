---
tags:
  - status/active
  - type/review
  - area/docs
---

# Code Review

The review workflow for this repo. The [`/deep-review`](../../.claude/skills/deep-review/SKILL.md)
skill drives the full lifecycle; this folder holds the authoritative process and checklist it
references.

> **Note:** the `deep-review` skill was copied from a portal backend and is still being adapted
> to this project. Where the skill mentions portal-specific tooling (a particular E2E suite,
> Camunda, internal libraries), treat the *process* as canonical and the *specifics* as TODO.
> Track adaptation in [`../to-do/planning/`](../to-do/planning/).

## Documents

- [`CODE-REVIEW-WORKFLOW.md`](CODE-REVIEW-WORKFLOW.md) — the phases a review goes through.
- [`CODE-REVIEW-CHECKLIST.md`](CODE-REVIEW-CHECKLIST.md) — per-finding checklist.

## Mulch-driven

Reviews are **expertise-driven**: before reviewing, prime the relevant Mulch domain
(`ml prime code-review-process`, `ml search "<topic>"`). After a review surfaces a durable
insight, record it (`ml record … && ml sync`). See [`../../CLAUDE.md`](../../CLAUDE.md) and the
[mulch skill](../../.claude/skills/mulch/SKILL.md).
