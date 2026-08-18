---
tags:
  - status/done
  - type/project
  - area/abac
  - area/catalog-service
---

# SPA-CHALLENGE-UX — STATUS-05 — T5: sup-demo / pm-demo, the seed's supervised block, the registry

> Filled in as the ticket is built (collaboratively). Records what was **measured** (spike results,
> Keycloak's observed prompt sequence, the pane cells' observations) — not what was intended.

**Status:** ✅ done — 2026-08-15

## Record

### What landed

- `infra/keycloak/realm-export.json` — **`sup-demo`** (`catalog-viewer` + `unit-supervisor`, password
  = username, a seeded `otp` credential with its **own** fixture secret `spachallengedemo1234`) and
  **`pm-demo`** (`catalog-editor`, password = username). Both carry a **pinned `id`**
  (`d3110000-…-0000000000a1` / `…a2`) — the first users in this export to pin one.
- `scripts/postman/seed-demo-data.sh` — a `# --- the supervised world ---` block: the two `d311…`
  catalogs (`…0002` *Demo Production Catalog*, `…0003` *Demo Open Catalog*), `Demo Production Team` /
  `Demo Open Team` with `pm-demo` `owner` on both, the edge `sup-demo -> {pm-demo}`, a category +
  product under each **through the gateway** as `pm-demo`, and the operator tag `env=production` on
  `…0002` via the catalog service's published `:28081` (response asserted). `sup-demo` is bound to
  nothing. Config gained `CATALOG_SERVICE`, the directory client, and the persona vars.
- `scripts/postman/demo-world-matrix.postman_collection.json` +
  `scripts/postman/run-demo-world-matrix.sh` — **new**, see the decision below.
- Docs: the `d311…` registry row + the `sup-demo`/`pm-demo` carve-out on the reserved-family
  paragraph (`scripts/postman/README.md`), the personas + the down-first re-import (`infra/README.md`),
  the demo-world matrix section + **the exported-flags rule** (`docs/guides/E2E-TESTING.md`).
- `mint-code-flow-token.py`: **unchanged**, as planned — `--print-otp --otp-secret` already existed.

### Decision taken in-build: where E31–E33 live

The package labels E31–E33 "newman, through the gateway" but T5's deliverables named **no collection
or runner**, and no newman collection was tied to `seed-demo-data.sh` (`run-spa-auth-smoke.sh` is
curl-based). Settled with the maintainer: a **new demo-world collection + runner**, rather than
shell self-checks or cells bolted onto a shipped matrix. It is the only runner in the suite that
**owns no fixtures** — it asserts a *seeded* world — which is exactly why it can run beside
everything else, and E32 proves that property in both directions.

### Measured — the realm spike

1. **The admin API IGNORES a pinned `id` on user create** (KC 26.3.2). `POST /admin/realms/…/users`
   with `"id": "d3110000-…a1"` answered **201** and assigned a fresh UUID (`c1ecf4ea-…`). Silent —
   no error, no warning.
2. **The realm IMPORT honours it.** After the transplant, two full `deploy.sh down` → `profile.sh up`
   → `up` cycles both returned `sup-demo = d3110000-…-0000000000a1` and
   `pm-demo = d3110000-…-0000000000a2`. **The pin works on the path that matters.** The contrast is
   the proof: on the same re-imports the *unpinned* personas churned (`editor` went
   `c182127f…` → `ef1f6677…`). So the demo world's subjects are now stable across re-imports —
   the class of breakage the smoke runner had to fix for `demo` cannot recur here. The seed still
   converges either way (find-or-create by subject, REPLACE edge), so nothing depends on the pin.
3. **`kc.sh export` cannot run beside a dev-mode Keycloak** — the running server holds an exclusive
   lock on the embedded H2 file (`MVStoreException: The file is locked`), and `--http-management-port`
   does not help (it is a DB lock, not a port clash). The H2 file lives in the container's writable
   layer, not a volume, so the working recipe is: **`docker commit` the running container → run
   `kc.sh export` in a container from the snapshot (deleting `*.lock.db` first) → `docker cp` the
   JSON out → drop the snapshot.** Non-destructive to the live rig.
4. **The exported OTP credential is byte-identical in shape to anna's**, which is what the
   spike-first rule was for — it was transplanted, never written from memory:
   `secretData: {"value":"spachallengedemo1234"}` /
   `credentialData: {"subType":"totp","digits":6,"counter":0,"period":30,"algorithm":"HmacSHA1"}`.
   The **password** stays the file's plaintext import idiom (the argon2 hash the export carries would
   have worked but broken the file's convention).
5. **The admin-API lookup is not optional.** Confirmed live: a direct grant for `sup-demo` with
   `-d otp=<code>` succeeds (`acr: aal1`, roles as seeded); the same grant **password-only** answers
   `invalid_grant / Invalid user credentials`. Keycloak's direct-grant flow demands a factor from any
   identity that owns one — exactly the design's stated reason for resolving her `sub` on the admin
   API with the `catalog-directory` service account instead.

### Measured — the world on the wire

The live challenge `sup-demo` receives on the production catalog's contents, verbatim:

```
HTTP/1.1 401
WWW-Authenticate: Bearer error="insufficient_user_authentication",
  error_description="A second factor is required to read production content",
  acr_values="aal2", max_age="300"
```

That is the exact shape **T2's parser** must accept (U1) and **T3's panel** renders. `pm-demo`, a
*member* of the same production catalog, reads the same contents **200** with no elevation.

### Measured — the rig trap that cost the first full run

`run-demo-world-matrix.sh`'s E32 pass runs `run-supervised-scope-matrix.sh`, whose E8 fault pass
recreates the catalog pods through `deploy.sh up`, forwarding the optional flavours as
`"${ENABLE_SPA:-0}"` / `"${ENABLE_MCP:-0}"` — and `deploy.sh`'s flag-off arms **tear those stacks
down**. A rig brought up with `ENABLE_SPA=1 ENABLE_MCP=1 ./deploy.sh up` (a command **prefix**, which
the shell does not export) therefore lost both the packaged SPA and the MCP server mid-run, and
`run-step-up-matrix.sh` then failed its own preflight on a dependency it never touched. The
supervised-scope runner **names this trap in its own comment**; nothing was broken, the flags simply
have to be **exported**. Handled two ways: `run-demo-world-matrix.sh` now exports
`ENABLE_SPA`/`ENABLE_MCP` (defaulting to 1) before handing control to the matrices, and the rule is
written into `docs/guides/E2E-TESTING.md`'s rig-flavours section as a blockquote. **This is the
`ENABLE_SPA`+`ENABLE_MCP` trap's second face** — the first (recorded in Mulch as `mx-9eab6a`) was
`deploy.sh` itself; this one is a *matrix runner* re-upping underneath you.

### The falsifier (the cells are not vacuous)

Deleting `sup-demo`'s **single** reporting edge — her only access path — and re-running the pass
collapsed **15 of E31's 29** assertions (`E31a sees EXACTLY 2`, `both by exact id`, and every
downstream contents cell), while the negative cell `the flagship demo catalog is NOT in her page`
correctly still passed. The world was restored by re-running the seed. So the page really is
*derived*, and the cells really do read it.

One other thing the run caught: the idempotency comparison first failed on a **formatting**
mismatch, not a real drift — python's `json.dumps` writes `", "` between elements and JS's
`JSON.stringify` writes `","`. Pinned with `separators=(',',':')`.

### Acceptance

| Cell | Result |
|---|---|
| **E31** (a–h) — the world exists and behaves | ✅ 29/29 |
| **Idempotency** (E31i–j) — a second seed run moves nothing | ✅ 6/6 |
| **E32** (a–d) — coexistence, both directions | ✅ 10/10 |
| **E33** (a–c) — convergence across a realm re-import | ✅ 7/7 |
| `run-supervised-scope-matrix.sh` after the seed | ✅ 42 + 6 |
| `run-step-up-matrix.sh` after the seed | ✅ 25 + 16 + 8 + 1 + 3 + 5 |

Both matrices ran on a rig brought up with **`ENABLE_SPA=1 ENABLE_MCP=1` together**, as T5's
acceptance requires. The seed re-run is a no-op.

### Deferred to T1 (stated, not silently dropped)

E31/E32/E33's text in [[10-QA-TEST-CASES]] also asserts the two demo catalogs come back
**`_provenance: "supervised"`**. That field does not exist yet — it is **T1's** deliverable. The
demo-world cells therefore ship asserting everything *except* provenance, and **T1 adds the
`_provenance` assertions to this collection** alongside its own `E30` block in the step-up matrix.
Recording it here rather than shipping a gated or vacuous assertion.

### Notes for the tickets that follow

- The pane cells can now be driven live: `sup-demo` / `sup-demo` + a TOTP from
  `mint-code-flow-token.py --print-otp --otp-secret spachallengedemo1234`.
- `…0002` challenges, `…0003` does not — that pair is what E10/E11 walk.
- Keep the flavour flags **exported** for the whole build, or the SPA disappears from under the
  Browser pane the first time a matrix runs.
