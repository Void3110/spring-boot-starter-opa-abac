#!/usr/bin/env bash
#
# Demo-world matrix (SPA-CHALLENGE-UX) — the console's supervised world, asserted through the gateway.
#
# What it proves (E31–E33 of docs/to-do/planning/SPA-CHALLENGE-UX/10-QA-TEST-CASES.md):
#   E31  the world seed-demo-data.sh builds exists and behaves: sup-demo, bound to NO team, sees
#        exactly the two d311… catalogs by DERIVATION; the untagged one opens with no ceremony; the
#        env=production one answers her a well-formed RFC 9470 challenge; and pm-demo — a MEMBER of
#        the very same catalog — reads it with no elevation at all. That asymmetry is the demo.
#   E32  the demo world and the matrices coexist, in BOTH directions: the supervised-scope and
#        step-up matrices leave sup-demo's page untouched, and no d311… row ever appears in
#        sup-anna's page. E31d, E32c and E33c each catch a rig left in the step-up FRESHNESS DRILL
#        (all three, because --skip-matrices and --convergence are separate entry points)
#        (max_age overridden to 5) — it compares the advertised window against the SHIPPED value
#        read from infra/opa/policies/step_up.json, never against live OPA, which the drill moves.
#   E33  the world converges across a realm re-import + re-seed, with no duplicate team or catalog.
#
# This runner OWNS NO FIXTURES and tears nothing down: it asserts a seeded world (family d311…,
# owned by seed-demo-data.sh — see README.md's registry). That is what makes it safe beside every
# other matrix. It is also why it must run AFTER the seed.
#
# Run AFTER:  ENABLE_SPA=1 ENABLE_MCP=1 ./deploy.sh up --pods 2   (BOTH flags on the SAME up —
#             deploy.sh tears down whichever stack its flag is missing, and E32 runs the step-up
#             matrix, whose preflight hard-fails without the MCP server)
#             scripts/postman/seed-demo-data.sh
# Then:       scripts/postman/run-demo-world-matrix.sh
#
# Usage:
#   ./run-demo-world-matrix.sh                 E31 + idempotency + E32 (runs the two matrices: slow)
#   ./run-demo-world-matrix.sh --skip-matrices E31 + idempotency only (a quick check of the world)
#   ./run-demo-world-matrix.sh --seed          run seed-demo-data.sh first, then the above
#   ./run-demo-world-matrix.sh --convergence   E33 only — run it after a realm re-import + a re-seed
#
# sup-demo and sup-anna both carry a TOTP, so every mint for them passes `-d otp=<code>`; Keycloak
# enforces codes ONE-TIME per credential, so a spent code is retried in the next 30 s window.
#
# The E32 pass EXPORTS ENABLE_SPA/ENABLE_MCP before running the matrices: the supervised-scope
# matrix recreates the catalog pods via `deploy.sh up`, whose flag-off arms would otherwise remove
# the packaged SPA and the MCP server this slice depends on. See the comment at that call site.

set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SELF_DIR/../.." && pwd)"
cd "$SELF_DIR"

# --- config (override via env) -----------------------------------------------
COLLECTION="${COLLECTION:-demo-world-matrix.postman_collection.json}"
ENV_FILE="${ENV_FILE:-local.postman_environment.json}"
NETWORK="${COMPOSE_NETWORK:-opa-abac-example_default}"
KEYCLOAK_TOKEN_URL="${KEYCLOAK_TOKEN_URL:-http://keycloak:8888/realms/catalog-demo/protocol/openid-connect/token}"
CLIENT_ID="${CLIENT_ID:-catalog-gateway}"
CLIENT_SECRET="${CLIENT_SECRET:-catalog-gateway-secret}"
GATEWAY="${GATEWAY:-http://localhost:9085}"
REPORT_DIR="${REPORT_DIR:-build/reports/postman}"
RUN_ID="${RUN_ID:-demo-world-$(date +%Y%m%d-%H%M%S)}"

# The seeded world (seed-demo-data.sh owns these ids).
DEMO_CATALOG_ID="${DEMO_CATALOG_ID:-d3110000-0000-0000-0000-000000000001}"
DEMO_PROD_CATALOG_ID="${DEMO_PROD_CATALOG_ID:-d3110000-0000-0000-0000-000000000002}"
DEMO_OPEN_CATALOG_ID="${DEMO_OPEN_CATALOG_ID:-d3110000-0000-0000-0000-000000000003}"
# The fixture TOTP secrets — public on purpose, like every fixture credential in this realm.
DEMO_OTP_SECRET="${DEMO_OTP_SECRET:-spachallengedemo1234}"
ANNA_OTP_SECRET="${ANNA_OTP_SECRET:-stepupdemofixture123}"

MODE=default
for arg in "$@"; do
  case "$arg" in
    --skip-matrices) MODE=skip-matrices ;;
    --convergence)   MODE=convergence ;;
    --seed)          RUN_SEED=1 ;;
    *) echo "ERROR: unknown argument '$arg' (see the usage block at the top)." >&2; exit 1 ;;
  esac
done

# --- preflight ---------------------------------------------------------------
# Same guard every sibling runner has: without it a missing env file surfaces as an opaque
# newman error several steps later, after the TOTP mints have already been spent.
[ -f "$ENV_FILE" ] || {
  echo "ERROR: $ENV_FILE not found. Copy: cp local.postman_environment.example.json local.postman_environment.json" >&2
  exit 1; }
RUNTIME=""
for c in docker podman; do command -v "$c" >/dev/null 2>&1 && { RUNTIME="$c"; break; }; done
[ -n "$RUNTIME" ] || { echo "ERROR: need docker or podman to mint in-network tokens." >&2; exit 1; }
command -v newman >/dev/null 2>&1 || { echo "ERROR: newman not found (npm i -g newman)." >&2; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "ERROR: python3 required." >&2; exit 1; }

# The SHIPPED step-up window, read from the policy DATA FILE — never from live OPA. The step-up
# matrix's freshness drill overrides max_age in OPA and restores it by restarting the container; a
# run that died mid-drill leaves 5 behind, and comparing live-against-live would pass vacuously.
SHIPPED_MAX_AGE="$(python3 -c "import json;print(json.load(open('$REPO_ROOT/infra/opa/policies/step_up.json'))['step_up']['max_age'])" || true)"
[ -n "$SHIPPED_MAX_AGE" ] || { echo "ERROR: could not read the shipped max_age from infra/opa/policies/step_up.json." >&2; exit 1; }

# --- helpers -----------------------------------------------------------------
mint() { # user pass [otp] -> access_token ('' on failure)
  local user="$1" pass="$2" otp="${3:-}" args=()
  args=(-d grant_type=password -d "client_id=$CLIENT_ID" -d "client_secret=$CLIENT_SECRET"
        -d "username=$user" -d "password=$pass")
  [ -n "$otp" ] && args+=(-d "otp=$otp")
  "$RUNTIME" run --rm --network "$NETWORK" curlimages/curl -s -X POST "$KEYCLOAK_TOKEN_URL" "${args[@]}" \
    | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p'
}

mint_otp() { # user pass secret -> access_token; retries ONCE in the next window (codes are one-time)
  local user="$1" pass="$2" secret="$3" code token
  code="$(python3 mint-code-flow-token.py --print-otp --otp-secret "$secret" || true)"
  token="$(mint "$user" "$pass" "$code" || true)"
  if [ -z "$token" ]; then
    echo "    ... '$user': the code for this window is spent or was rejected; waiting for the next one" >&2
    python3 -c "import time;p=30;time.sleep(p-(time.time()%p)+1)"
    code="$(python3 mint-code-flow-token.py --print-otp --otp-secret "$secret" || true)"
    token="$(mint "$user" "$pass" "$code" || true)"
  fi
  printf '%s' "$token"
}

require_token() { # name token
  [ -n "$2" ] || {
    echo "ERROR: could not mint a token for '$1'." >&2
    echo "  If '$1' is sup-demo/pm-demo, the running realm predates the demo supervisor personas." >&2
    echo "  Re-import it, then re-seed:" >&2
    echo "    ./deploy.sh down && ./profile.sh up && ENABLE_SPA=1 ENABLE_MCP=1 ./deploy.sh up --pods 2" >&2
    echo "    cd scripts/postman && ./seed-demo-data.sh" >&2
    exit 1; }
}

run_folder() { # folder report-suffix [extra --env-var pairs...]
  local folder="$1" suffix="$2"; shift 2
  newman run "$COLLECTION" \
    -e "$ENV_FILE" \
    --folder "$folder" \
    --env-var "gateway=$GATEWAY" \
    --env-var "collection_base_url=$GATEWAY/api/v1" \
    --env-var "demo_catalog_id=$DEMO_CATALOG_ID" \
    --env-var "demo_prod_catalog_id=$DEMO_PROD_CATALOG_ID" \
    --env-var "demo_open_catalog_id=$DEMO_OPEN_CATALOG_ID" \
    --env-var "shipped_max_age=$SHIPPED_MAX_AGE" \
    --env-var "sup_token=$SUP_TOKEN" \
    --env-var "pm_token=$PM_TOKEN" \
    --env-var "anna_token=${ANNA_TOKEN:-}" \
    "$@" \
    --reporters cli,json \
    --reporter-json-export "$REPORT_DIR/$RUN_ID/$suffix"
}

# A live read of the seeded world, used to pin the pre-re-seed ids the idempotency cells compare to.
observe() { # token url jq-ish-python -> value
  curl -s -H "Authorization: Bearer $1" "$2" | python3 -c "$3"
}

mkdir -p "$REPORT_DIR/$RUN_ID"

if [ "${RUN_SEED:-0}" = "1" ]; then
  echo "==> Seeding the demo world first (--seed) ..."
  ./seed-demo-data.sh >/dev/null || { echo "ERROR: seed-demo-data.sh failed — run it directly to see why." >&2; exit 1; }
fi

echo "==> Minting the demo personas (sup-demo needs her TOTP; Keycloak's direct grant demands it) ..."
SUP_TOKEN="$(mint_otp sup-demo sup-demo "$DEMO_OTP_SECRET" || true)"; require_token sup-demo "$SUP_TOKEN"
PM_TOKEN="$(mint pm-demo pm-demo || true)";                           require_token pm-demo "$PM_TOKEN"
echo "  sup-demo + pm-demo minted; shipped step-up window = ${SHIPPED_MAX_AGE}s"

# --- E33 only ----------------------------------------------------------------
if [ "$MODE" = convergence ]; then
  echo "==> Convergence (E33): the world after a realm re-import + a re-seed ..."
  # Recorded, not asserted (10-QA-TEST-CASES E33): whether the subjects were STABLE across the
  # re-import (the realm export pins both users' id) or fresh (the seed re-bootstrapped by subject
  # and REPLACEd the edge). Either way the world converges — that is what the cells assert.
  sub_of() { printf '%s' "$1" | cut -d. -f2 | python3 -c "
import sys,base64,json
p=sys.stdin.read().strip(); p+='='*(-len(p)%4)
print(json.loads(base64.urlsafe_b64decode(p))['sub'])"; }
  echo "  OBSERVED subjects after the re-import: sup-demo=$(sub_of "$SUP_TOKEN") pm-demo=$(sub_of "$PM_TOKEN")"
  echo "    (the realm export pins d3110000-…-0000000000a1 / …a2 — equal means the pin was honoured)"
  run_folder "Convergence" "convergence.json"
  echo ""
  echo "==> Convergence green. Report: $REPORT_DIR/$RUN_ID/"
  exit 0
fi

# --- E31 ---------------------------------------------------------------------
echo "==> Demo world (E31): the seeded world through the gateway ..."
run_folder "Demo world" "demo-world.json"

# --- E31 idempotency ---------------------------------------------------------
# Pin what the world looks like NOW, re-run the seed, and assert nothing moved. Find-or-create (not
# delete-and-recreate) is what makes the ids survive, and this is the cell that keeps it that way.
echo "==> Pinning the pre-re-seed ids ..."
# separators=(',',':') so the string is byte-comparable with the cell's JSON.stringify — python's
# default json.dumps writes ", " between elements and JS writes ",".
PRE_CATALOG_IDS="$(observe "$SUP_TOKEN" "$GATEWAY/api/v1/catalogs?perPage=50" \
  "import sys,json;print(json.dumps(sorted(i['id'] for i in json.load(sys.stdin)['items']),separators=(',',':')))" || true)"
PRE_PROD_CATEGORY_ID="$(observe "$PM_TOKEN" "$GATEWAY/api/v1/catalogs/$DEMO_PROD_CATALOG_ID/categories" \
  "import sys,json;print(json.load(sys.stdin)['items'][0]['id'])" || true)"
# The PRODUCT id too — E31j's name has always claimed products survive the re-seed, but nothing
# pinned one, so the claim went unasserted (E31k is the cell that now checks it).
PRE_PROD_PRODUCT_ID="$(observe "$PM_TOKEN" \
  "$GATEWAY/api/v1/catalogs/$DEMO_PROD_CATALOG_ID/categories/$PRE_PROD_CATEGORY_ID/products" \
  "import sys,json;print(json.load(sys.stdin)['items'][0]['id'])" || true)"
# `|| true` on each observe above is load-bearing: under `set -euo pipefail` a failing command
# substitution in an assignment aborts the script immediately, so without it this friendly guard
# could never fire and the operator got a bare exit instead of the reason.
[ -n "$PRE_CATALOG_IDS" ] && [ -n "$PRE_PROD_CATEGORY_ID" ] && [ -n "$PRE_PROD_PRODUCT_ID" ] || {
  echo "ERROR: could not observe the seeded world before the re-seed." >&2; exit 1; }

echo "==> Re-running the seed (it must be a no-op) ..."
./seed-demo-data.sh >/dev/null || { echo "ERROR: the seed re-run failed — it is not idempotent." >&2; exit 1; }

echo "==> Idempotency (E31i–k): the ids after a second seed run ..."
run_folder "Idempotency" "idempotency.json" \
  --env-var "pre_reseed_catalog_ids=$PRE_CATALOG_IDS" \
  --env-var "pre_reseed_prod_category_id=$PRE_PROD_CATEGORY_ID" \
  --env-var "pre_reseed_prod_product_id=$PRE_PROD_PRODUCT_ID"

if [ "$MODE" = skip-matrices ]; then
  echo ""
  echo "==> Demo world + idempotency green (E32 skipped: --skip-matrices)."
  echo "    Report: $REPORT_DIR/$RUN_ID/"
  exit 0
fi

# --- E32 ---------------------------------------------------------------------
# The matrices own their own fixtures and tear them down; this runner touches neither. If either
# ever starts clearing sup-demo's edge or unbinding pm-demo, E32a is where it surfaces.
echo "==> Coexistence (E32): running the supervised-scope and step-up matrices ..."
# EXPORT the flavour flags before handing control to the matrices. run-supervised-scope-matrix.sh's
# E8 pass recreates the catalog pods through `deploy.sh up`, forwarding ENABLE_SPA/ENABLE_MCP as
# "${VAR:-0}" — and deploy.sh's flag-off arms TEAR THE STACKS DOWN. A rig brought up with the flags
# as a command PREFIX (`ENABLE_SPA=1 ENABLE_MCP=1 ./deploy.sh up`) does not export them, so that
# re-up reads 0 and removes the packaged SPA and the MCP server: the step-up matrix's preflight then
# hard-fails on its own missing dependency, and the pane cells lose the console they run against.
# Measured while building this runner; the supervised-scope runner names the trap in its own comment.
export ENABLE_SPA="${ENABLE_SPA:-1}"
export ENABLE_MCP="${ENABLE_MCP:-1}"
export ENABLE_OIDC="${ENABLE_OIDC:-1}"
export ENABLE_USER_SERVICE="${ENABLE_USER_SERVICE:-1}"
./run-supervised-scope-matrix.sh || { echo "ERROR: run-supervised-scope-matrix.sh failed — fix it before reading E32." >&2; exit 1; }
./run-step-up-matrix.sh          || { echo "ERROR: run-step-up-matrix.sh failed — fix it before reading E32." >&2; exit 1; }

echo "==> Re-minting for the coexistence pass (the matrices consumed their own TOTP windows) ..."
SUP_TOKEN="$(mint_otp sup-demo sup-demo "$DEMO_OTP_SECRET" || true)"; require_token sup-demo "$SUP_TOKEN"
PM_TOKEN="$(mint pm-demo pm-demo || true)";                           require_token pm-demo "$PM_TOKEN"
ANNA_TOKEN="$(mint_otp sup-anna sup-anna "$ANNA_OTP_SECRET" || true)"; require_token sup-anna "$ANNA_TOKEN"

echo "==> Coexistence (E32a–d): the demo world after the matrices ran ..."
run_folder "Coexistence" "coexistence.json"

echo ""
echo "==> Demo-world matrix green (E31, idempotency, E32)."
echo "    Report: $REPORT_DIR/$RUN_ID/"
echo "    E33 (convergence) is a separate pass: re-import the realm, re-seed, then"
echo "      ./run-demo-world-matrix.sh --convergence"
