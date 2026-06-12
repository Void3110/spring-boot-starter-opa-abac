---
tags: [index, docs]
---

# Documentation

Documentation for **spring-boot-starter-opa-abac** — a production-grade ABAC authorization
starter for Spring Boot backed by Open Policy Agent (OPA), plus a runnable example.

This `docs/` folder is an **Obsidian vault**. Open the repo root (or this folder) in Obsidian
to navigate with backlinks and graph view. `[[wikilinks]]` resolve to notes by their filename.

## Structure

| Folder | Purpose |
|--------|---------|
| [`architecture/`](architecture/) | How the library and example are designed — the ABAC model, OPA integration, the gateway↔app two-layer authorization, module boundaries. Dated decision records live in [`architecture/adr/`](architecture/adr/). |
| [`guides/`](guides/) | How-to guides — adding an ABAC-secured endpoint, writing Rego policies, wiring Keycloak/APISIX/OPA, the OpenAPI codegen flow. |
| [`api/`](api/) | Reserved for a written API reference (currently empty). Today the API contract lives in the **OpenAPI specs** (`example-*/src/main/resources/openapi/*.yaml`, the codegen source of truth) and each running service's Swagger UI at `/swagger-ui.html`. |
| [`code-review/`](code-review/) | The review workflow and checklist used by the `/deep-review` skill. |
| [`article/`](article/) | Working notes for the article series — arguments and worked examples accumulated while building, to be turned into publishable prose later. |
| [`to-do/`](to-do/) | Feature work, organized by lifecycle. See below. |

## `to-do/` lifecycle

Per-feature folders move through three stages:

```
to-do/
├── planning/      # design docs for work not yet started or in progress
├── implemented/   # shipped — moved here with a status banner when done
└── cancelled/     # abandoned — kept for the rationale
```

Create a folder per feature (e.g. `to-do/planning/USER-SERVICE/`) and put its design,
decomposition, and progress notes inside. When it ships, `git mv` the folder to
`implemented/` and add a one-line "Shipped (date)" banner at the top of its main note.

**Decisions vs. designs.** A feature folder's `00-DESIGN` / `01-DECOMPOSITION` are *living*
plans — they get rewritten as the work evolves. A **structural decision** taken during
planning (a schema/authority shape, a module boundary, a "match here not there", an
additive-vs-breaking choice) is *not* living: it gets its own **ADR** in
[`architecture/adr/`](architecture/adr/) so the rationale and the rejected alternatives
survive as a dated, supersede-able record. As a planning step, whenever a decomposition
surfaces a fork worth defending later, write (or update) the ADR up front and link it from
the design — don't leave the *why* buried in a decomposition that will be rewritten or moved
to `implemented/`.

## Conventions

- **Tags**: see [`TAG-SYSTEM.md`](TAG-SYSTEM.md). Every note carries `status/*` and `type/*` (+ `area/*`).
- **Filenames**: `UPPER-KEBAB-CASE.md` for guides/architecture/reference notes; feature folders may use the feature name.
- **Links**: prefer Obsidian `[[wikilinks]]` within the vault; use relative paths when linking files outside it.

## Clean-room note

This is a public, clean-room project. Documentation describes **this** library — it must not
copy text, identifiers, or designs from any proprietary source. See the root `CLAUDE.md`
"IP Boundary" section.
