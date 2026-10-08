#!/usr/bin/env bash
#
# list-filter-sql-volume.sh — what the partial-eval list filter's SQL costs at volume.
#
# No rig, no OPA, no JVM: a throwaway Postgres (the repo's compose image) seeded with ~1M product rows —
# 999 categories x 800 rows plus one "big" category of 200,000 — under the example's index layout (B-tree
# on the scope column, GIN on `tags`). It then times the exact predicate shapes the library emits
# (ResidualSpecificationFactory + JsonPathDialect: EQ/IN via jsonb_extract_path_text, CONTAINS via
# jsonb_exists(jsonb_extract_path(...)), AND-ed with the abac_deny mirror), paged the way Spring Data pages
# a list (ORDER BY created_at, id … LIMIT 20, plus a separate count(*) for the total). It also times the
# containment form (tags @> …) — the GIN-servable candidate the guide records as open work — and checks
# that both forms match the same rows on this seed.
#
# Each figure is the median of RUNS `EXPLAIN ANALYZE` executions on a warm cache: an order of magnitude
# for one machine, not a benchmark. Recorded results and their reading:
# docs/guides/PARTIAL-EVALUATION-FILTERING.md §"Indexing".
#
# Prereq: the docker CLI (Docker, or podman with the docker shim).
#   scripts/bench/list-filter-sql-volume.sh                     # RUNS=5, postgres:16-alpine
#   RUNS=9 IMAGE=postgres:17-alpine scripts/bench/list-filter-sql-volume.sh

set -euo pipefail

IMAGE="${IMAGE:-postgres:16-alpine}"
RUNS="${RUNS:-5}"
NAME="opa-abac-list-filter-bench-$$"

cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT

psql_q() { docker exec -i "$NAME" psql -U postgres -qtAX -v ON_ERROR_STOP=1 "$@"; }

echo "Starting $IMAGE as $NAME ..."
docker run -d --rm --name "$NAME" -e POSTGRES_PASSWORD=bench "$IMAGE" >/dev/null

# The official image runs a temporary, socket-only server for its init phase and then restarts. A
# `docker exec psql` connects over that same socket, so "accepting connections" alone can hit the
# temporary server; wait for the init-complete line first.
ready=0
for _ in $(seq 1 90); do
  if docker logs "$NAME" 2>&1 | grep -q 'PostgreSQL init process complete' \
     && docker exec "$NAME" pg_isready -U postgres >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 1
done
[ "$ready" = 1 ] || { echo "Postgres in $NAME did not become ready within 90 s"; exit 1; }

SERVER_VERSION="$(psql_q -c 'SHOW server_version')" || true
[ -n "$SERVER_VERSION" ] || { echo "Could not read server_version from $NAME"; exit 1; }

echo "Seeding ~1M rows (Postgres $SERVER_VERSION) ..."
psql_q <<'SQL'
CREATE TABLE product (
    id          bigserial PRIMARY KEY,
    category_id int         NOT NULL,
    tags        jsonb       NOT NULL DEFAULT '{}',
    created_at  timestamptz NOT NULL
);
-- 999 ordinary categories x 800 rows + category 0 with 200,000 rows = 999,200 rows.
-- region: mena 1 % (selective), emea 20 % (half scalar, half array), apac 30 %, amer the rest;
-- every 250th row carries abac_deny so the deny mirror has something to remove.
INSERT INTO product (category_id, tags, created_at)
SELECT CASE WHEN g <= 200000 THEN 0 ELSE 1 + (g % 999) END,
       CASE WHEN g % 100 = 0        THEN jsonb_build_object('region', 'mena')
            WHEN g % 10 = 1         THEN jsonb_build_object('region', 'emea')
            WHEN g % 10 = 2         THEN jsonb_build_object('region', jsonb_build_array('emea', 'amer'))
            WHEN g % 10 IN (3, 4, 5) THEN jsonb_build_object('region', 'apac')
            ELSE                         jsonb_build_object('region', 'amer') END
         || CASE WHEN g % 250 = 7 THEN '{"abac_deny": "true"}'::jsonb ELSE '{}'::jsonb END,
       now() - (g || ' seconds')::interval
FROM generate_series(1, 999200) AS g;
CREATE INDEX idx_product_category ON product (category_id);  -- the scope column (B-tree)
CREATE INDEX idx_product_tags     ON product USING gin (tags); -- as the example's changelogs (jsonb_ops)
VACUUM ANALYZE product;
SQL

# The predicate shapes, verbatim from the library's dialect (see the file header).
emitted() { echo "(jsonb_extract_path_text(tags,'region') = '$1' OR jsonb_exists(jsonb_extract_path(tags,'region'),'$1'))"; }
contained() { echo "(tags @> '{\"region\":\"$1\"}' OR tags @> '{\"region\":[\"$1\"]}')"; }
NOT_DENIED="(jsonb_extract_path_text(tags,'abac_deny') IS NULL OR jsonb_extract_path_text(tags,'abac_deny') <> 'true')"
PAGE="ORDER BY created_at, id OFFSET 0 LIMIT 20"

# Median Execution Time (ms) of RUNS EXPLAIN ANALYZE runs, plus the scan nodes the plan uses.
measure() {
  local label="$1" sql="$2" i t plan
  local -a times=()
  for ((i = 0; i < RUNS; i++)); do
    t="$(psql_q -c "EXPLAIN (ANALYZE) $sql" | sed -n 's/^Execution Time: \([0-9.]*\) ms$/\1/p')" || true
    [ -n "$t" ] || { echo "No 'Execution Time' in EXPLAIN ANALYZE output for: $label"; exit 1; }
    times+=("$t")
  done
  plan="$(psql_q -c "EXPLAIN $sql" \
    | grep -o -E '(Seq Scan|Bitmap Index Scan|Index Scan|Index Only Scan) (on|using) [a-z_]+' \
    | sed -E 's/^(Seq Scan|Bitmap Index Scan|Index Scan|Index Only Scan) (on|using) //' \
    | sort -u | paste -sd, -)" || true
  printf '| %-48s | %10s | %-38s |\n' "$label" \
    "$(printf '%s\n' "${times[@]}" | sort -n | awk -v n="$RUNS" 'NR == int((n + 1) / 2)')" "${plan:-?}"
}

echo
echo "Postgres $SERVER_VERSION, $RUNS runs per row, median ms"
echo
printf '| %-48s | %10s | %-38s |\n' "query (scope rows / predicate / match rate)" "median ms" "scans (index or table)"
printf '|%s|%s|%s|\n' "$(printf -- '-%.0s' {1..50})" "$(printf -- '-%.0s' {1..12})" "$(printf -- '-%.0s' {1..40})"
measure "page  / 800     / emitted / 20 %"           "SELECT * FROM product WHERE category_id = 17 AND $(emitted emea) AND $NOT_DENIED $PAGE"
measure "count / 800     / emitted / 20 %"           "SELECT count(*) FROM product WHERE category_id = 17 AND $(emitted emea) AND $NOT_DENIED"
measure "page  / 200 000 / emitted / 20 %"           "SELECT * FROM product WHERE category_id = 0 AND $(emitted emea) AND $NOT_DENIED $PAGE"
measure "count / 200 000 / emitted / 20 %"           "SELECT count(*) FROM product WHERE category_id = 0 AND $(emitted emea) AND $NOT_DENIED"
measure "page  / 200 000 / emitted / 1 %"            "SELECT * FROM product WHERE category_id = 0 AND $(emitted mena) AND $NOT_DENIED $PAGE"
measure "count / 200 000 / emitted / 1 %"            "SELECT count(*) FROM product WHERE category_id = 0 AND $(emitted mena) AND $NOT_DENIED"
measure "count / 200 000 / no residual (allow-all)"  "SELECT count(*) FROM product WHERE category_id = 0 AND $NOT_DENIED"
measure "page  / 200 000 / containment / 1 %"        "SELECT * FROM product WHERE category_id = 0 AND $(contained mena) AND $NOT_DENIED $PAGE"
measure "count / 200 000 / containment / 1 %"        "SELECT count(*) FROM product WHERE category_id = 0 AND $(contained mena) AND $NOT_DENIED"
measure "count / 200 000 / containment / 20 %"       "SELECT count(*) FROM product WHERE category_id = 0 AND $(contained emea) AND $NOT_DENIED"

SAME="$(psql_q -c "SELECT (SELECT count(*) FROM product WHERE $(emitted emea)) || ' = ' || (SELECT count(*) FROM product WHERE $(contained emea))")" || true
[ -n "$SAME" ] || { echo "Could not compare the emitted and containment row counts"; exit 1; }
echo
echo "Rows matching emitted vs containment (region = emea, whole table): $SAME"
echo "(Equal on this seed's string scalars and string arrays only — not a proof for numbers or objects.)"
