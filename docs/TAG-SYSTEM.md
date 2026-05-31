---
tags: [index, docs, meta]
---

# Tag System

A small, consistent tag vocabulary so notes are filterable in Obsidian (and greppable).
Put tags in YAML front-matter at the top of every note.

```yaml
---
tags:
  - status/planned
  - type/architecture
  - area/opa
---
```

## Dimensions

Use **one `status/`**, **one `type/`**, and **one or more `area/`** tags per note.

### `status/` — lifecycle

| Tag | Meaning |
|-----|---------|
| `status/planned` | Designed, not started |
| `status/in-progress` | Being implemented |
| `status/done` | Shipped (note usually lives in `to-do/implemented/`) |
| `status/cancelled` | Abandoned (note usually lives in `to-do/cancelled/`) |
| `status/active` | Living reference/guide that stays current |

### `type/` — kind of note

| Tag | Meaning |
|-----|---------|
| `type/architecture` | Design / how-it-works |
| `type/guide` | How-to / step-by-step |
| `type/api` | API reference |
| `type/project` | Feature work / decomposition / progress |
| `type/decision` | A recorded decision + rationale (ADR-style) |
| `type/research` | Investigation / landscape / options |
| `type/review` | Code-review process / checklist |
| `type/index` | Index / map-of-content |

### `area/` — subject area

`area/opa` · `area/rego` · `area/abac` · `area/spring` · `area/spring-security`
· `area/spring-data` · `area/keycloak` · `area/apisix` · `area/tracing`
· `area/catalog-service` · `area/user-service` · `area/build` · `area/docs`

## Conventions

- Lifecycle is communicated by **both** the `status/*` tag **and** the folder under `to-do/`.
  When a feature ships, move its folder to `implemented/` *and* flip `status/in-progress` → `status/done`.
- Keep the vocabulary small. Add a new `area/*` only when a real subject area appears; don't
  invent per-note tags.
