---
tags:
  - status/done
  - type/project
  - area/api
  - area/docs
---

# STATUS T5 — Docs (guide §9 Targets → adopted §3/§4/§6) + roadmap + Mulch + folder move

> ✅ **Shipped.** Docs promotion + roadmap flip + a post-run deep-review fix + the `autonomous-runs`
> record + the folder move to `implemented/`.

## What shipped

- **`docs/guides/REST-API-DESIGN.md`** — the two §9 *Targets* moved into the adopted body:
  - **§6 Error handling** rewritten as the **adopted** RFC-7807 `application/problem+json` `ProblemDetail`
    envelope (the seven members, **no `message`**), the library-owned `ApiErrorCode` interface +
    `LibraryErrorCode` + per-app enum, **semantic granularity**, the typed `errorCode` in the spec, and
    the `AccessDeniedException`→403 base mapping; the exception→`errorCode` mapping table.
  - **§4** — error responses are `application/problem+json`; **`Location` on every `201`** is the rule.
  - **§3** — a note that a status pairs with a typed `errorCode` (discriminate *within* a status).
  - **§10 checklist** — the advice item now says `ProblemDetail`/`errorCode`; the 201 `Location` item
    lost its *(target)* marker.
  - **§9 Targets** — the two adopted rows removed; pagination (now flagged **Phase 5.95**) + actions
    (Phase 6) remain. ADR 0011 referenced (rationale stays in the ADR, not copied into the guide).
- **`POC-ROADMAP.md`** — **Phase 5.9 flipped to ✅ DONE** with the shipped summary; 5.95 (pagination) is
  the next slice.
- **Post-run `/deep-review` fix (Low finding):** the catalog spec defined a `Forbidden` response component
  referenced by **0** paths. Every catalog operation is `@OpaPreAuthorize`-gated, so `'403': Forbidden`
  is now wired onto **all 13** catalog operations — the contract declares the 403 the runtime already
  renders (codegen + compile stay clean).
- **Root/project `CLAUDE.md`** — no change (no new build/run step; the rig gotcha is environment, recorded
  to Mulch not CLAUDE.md).
- **Folder moved** `docs/to-do/planning/REST-API-REFINEMENT/` → `docs/to-do/implemented/REST-API-REFINEMENT/`
  with a past-tense **Shipped** banner; the index frontmatter flipped `status/planning` → `status/done`.

## Tests

N/A (docs + spec + Mulch + folder move). `:example-catalog-management-service:compileJava` stays green
after the 403 spec wiring (codegen clean). Clean-room scan clean across all touched files.

## Architecture review + refactor (the ★ gate)

- **Post-run deep-review (flow phase ④):** an adversarial general-purpose review of the whole branch vs
  `main` confirmed **no security regression** — every error path returns the **same status** as before;
  the 403 deny renders, never authorizes; `opa-abac-core` untouched; clean replacement (no `message`); the
  vocabulary typed; `Location` keyed by the addressable id. **One Low finding** (the unused catalog
  `Forbidden` component) — **fixed** in the T5 commit. No Critical/Medium.
- **ADR 0011 unchanged** — the rationale lives in the ADR; the guide references it.

## Integration / e2e

N/A (no behavior change). The slice's e2e was T4.

## Decisions

- **Root `CLAUDE.md` left unchanged** — no new command; the `deploy.sh build` usermgmt-rebuild gotcha is a
  rig fact recorded to Mulch (`opa-abac`), not a project instruction.
- **Wire 403 onto every catalog op** (deep-review fix) rather than drop the `Forbidden` component — the
  contract should declare what the runtime emits.

## Commit

`docs: adopt the problem+json error contract in the guide + flip roadmap; declare 403 in catalog spec`
(`a12d2d3`) + the folder-move commit + the `autonomous-runs` reference record (`.mulch`-only sync), all on
`feature/void3110/rest-api-refinement`.
