#!/usr/bin/env bash
#
# Incremental PostGIS DB sync for the Orenda "poi" tables.
#
# Keeps two machines' databases in sync periodically. This machine usually has
# MORE / NEWER data, so it acts as the SOURCE. We export ONLY the rows that are
# new or changed since the last sync, then import them on the other machine with
#   INSERT ... ON CONFLICT (osm_id, osm_type) DO UPDATE
# so nothing is duplicated and only changed rows are written.
#
# The `poi` table is keyed by UNIQUE (osm_id, osm_type) and has updated_at /
# created_at, so it is ideal for incremental sync.
#
# USAGE — SOURCE (this machine, the one with more data):
#   ./sync-db.sh export            # incremental since last sync (or full on 1st run)
#   ./sync-db.sh export --since "2026-08-19 00:00:00"
#   ./sync-db.sh export --full     # everything, ignore last-sync marker
#
#   Produces files under ./exports/
#
# USAGE — TARGET (the other machine):
#   # copy the exports/ directory over, e.g.:
#   scp -r sajad@source:/home/sajad/Orenda/exports ./exports
#   ./sync-db.sh import ./exports
#
# Importing is idempotent and safe to run repeatedly.
#
# REQUIREMENTS (both machines):
#   - The source DB is reachable via `docker exec aitp-pg` (the app's PostGIS
#     container). Adjust CONTAINER if different.
#   - The target psql/pg_dump must be reachable via the SAME container approach,
#     OR set DB_HOST/DB_PORT/DB_USER/DB_PASS to use a direct connection.
#
# If your other machine does NOT use this docker container, set the psql
# connection env vars below and it will use psql directly on the host.

set -euo pipefail

# --- Connection settings (used on the TARGET import side; source uses docker) ---
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-aitp}"
DB_USER="${DB_USER:-postgres}"
DB_PASS="${DB_PASS:-postgres}"
CONTAINER="${CONTAINER:-aitp-pg}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MARKER="$SCRIPT_DIR/.last_sync_timestamp"
NOW="$(date -u +%Y-%m-%dT%H:%M:%S)"

# ---------------------------------------------------------------------------
# Run psql on the SOURCE via docker (or on TARGET via direct connection).
# We pass SQL on stdin.
# ---------------------------------------------------------------------------
run_src() {
    # source = docker container
    docker exec -i -e PGPASSWORD="$DB_PASS" "$CONTAINER" \
        psql -v ON_ERROR_STOP=0 -U "$DB_USER" -d "$DB_NAME" "$@"
}
run_tgt() {
    # target = direct psql connection (set env vars)
    PGPASSWORD="$DB_PASS" psql -v ON_ERROR_STOP=0 \
        -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" "$@"
}

usage() {
    sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
}

# ---------------------------------------------------------------------------
# EXPORT — run on the SOURCE machine
# ---------------------------------------------------------------------------
do_export() {
    local since="$1" full="$2"
    mkdir -p exports

    if [ "$full" = "1" ]; then
        since=""; echo "== FULL export (all rows) =="
    elif [ -n "$since" ]; then
        echo "== Incremental export since: $since =="
    elif [ -f "$MARKER" ]; then
        since="$(cat "$MARKER")"
        echo "== Incremental export since last sync: $since =="
    else
        since=""; echo "== No last-sync marker; FULL export on first run =="
    fi

    # ---- 1) Schema ----
    echo "Dumping schema..."
    docker exec "$CONTAINER" pg_dump -U "$DB_USER" -d "$DB_NAME" \
        --schema-only --no-owner --no-privileges > exports/schema.sql
    # Ensure extensions exist on target (idempotent)
    cat > exports/extensions.sql <<'EOF'
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS uuid-ossp;
EOF

    # ---- 2) poi data into a staging-friendly dump ----
    # We dump changed rows (or all) into a temp table, then export that temp
    # table's data with column inserts so it can be loaded back and upserted.
    if [ -n "$since" ]; then
        echo "Extracting poi rows changed since $since ..."
        docker exec "$CONTAINER" psql -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB_NAME" -q \
            -c "DROP TABLE IF EXISTS tmp_sync_poi;" \
            -c "CREATE TABLE tmp_sync_poi (LIKE poi INCLUDING ALL);" \
            -c "INSERT INTO tmp_sync_poi SELECT * FROM poi WHERE updated_at >= '$since'::timestamptz OR created_at >= '$since'::timestamptz;"
    else
        echo "Staging full poi data ..."
        docker exec "$CONTAINER" psql -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB_NAME" -q \
            -c "DROP TABLE IF EXISTS tmp_sync_poi;" \
            -c "CREATE TABLE tmp_sync_poi (LIKE poi INCLUDING ALL);" \
            -c "INSERT INTO tmp_sync_poi SELECT * FROM poi;"
    fi

    echo "Dumping poi data (column inserts) ..."
    docker exec "$CONTAINER" pg_dump -U "$DB_USER" -d "$DB_NAME" \
        -t tmp_sync_poi --data-only --no-owner --inserts --column-inserts \
        > exports/poi.data.sql
    docker exec "$CONTAINER" psql -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB_NAME" -q \
        -c "DROP TABLE IF EXISTS tmp_sync_poi;"

    # ---- 3) Related tables ----
    for t in poi_source_data poi_feedback preference_feedback; do
        echo "Dumping $t ..."
        docker exec "$CONTAINER" pg_dump -U "$DB_USER" -d "$DB_NAME" \
            -t "$t" --data-only --no-owner --inserts --column-inserts \
            > "exports/$t.data.sql"
    done

    # ---- 4) Mark sync point ----
    echo "$NOW" > "$MARKER"
    echo
    echo "Export complete. Files:"
    ls -la exports/
    echo
    echo "Sync marker set to: $NOW"
    echo "Copy ./exports/ to the target machine, then run:"
    echo "  ./sync-db.sh import ./exports"
}

# ---------------------------------------------------------------------------
# IMPORT — run on the TARGET machine
# ---------------------------------------------------------------------------
do_import() {
    local dir="${1:-exports}"
    [ -f "$dir/schema.sql" ] || { echo "ERROR: $dir/schema.sql not found" >&2; exit 1; }
    [ -f "$dir/poi.data.sql" ] || { echo "ERROR: $dir/poi.data.sql not found" >&2; exit 1; }

    echo "== Applying extensions (idempotent) =="
    run_tgt -f "$dir/extensions.sql" 2>/dev/null || true

    echo "== Applying schema (idempotent, ignores 'already exists') =="
    # Create staging table used for upsert.
    run_tgt <<'SQL'
CREATE TABLE IF NOT EXISTS sync_stage_poi (LIKE poi INCLUDING ALL);
TRUNCATE sync_stage_poi;
SQL

    echo "== Loading poi data into staging table =="
    # The dump contains INSERTs INTO tmp_sync_poi; rewrite to staging table.
    sed 's/tmp_sync_poi/sync_stage_poi/g' "$dir/poi.data.sql" \
        | run_tgt

    echo "== Upserting staged rows into poi (new + updated) =="
    run_tgt <<'SQL'
INSERT INTO poi (
    id, osm_id, osm_type, wikidata_id, name_tr, name_en,
    category, subcategory, location, boundary,
    completeness_score, data_sources, attributes, verified,
    last_synced_at, created_at, updated_at
)
SELECT
    s.id, s.osm_id, s.osm_type, s.wikidata_id, s.name_tr, s.name_en,
    s.category, s.subcategory, s.location, s.boundary,
    s.completeness_score, s.data_sources, s.attributes, s.verified,
    s.last_synced_at, s.created_at, s.updated_at
FROM sync_stage_poi s
ON CONFLICT (osm_id, osm_type) DO UPDATE SET
    wikidata_id        = COALESCE(EXCLUDED.wikidata_id, poi.wikidata_id),
    name_tr            = COALESCE(NULLIF(EXCLUDED.name_tr,''), poi.name_tr),
    name_en            = COALESCE(EXCLUDED.name_en, poi.name_en),
    category           = EXCLUDED.category,
    subcategory        = EXCLUDED.subcategory,
    location           = COALESCE(EXCLUDED.location, poi.location),
    boundary           = COALESCE(EXCLUDED.boundary, poi.boundary),
    completeness_score = GREATEST(poi.completeness_score, EXCLUDED.completeness_score),
    data_sources       = CASE WHEN poi.data_sources @> EXCLUDED.data_sources
                              THEN poi.data_sources
                              ELSE (SELECT ARRAY(SELECT DISTINCT unnest(poi.data_sources || EXCLUDED.data_sources))) END,
    attributes         = poi.attributes || EXCLUDED.attributes,
    verified           = poi.verified OR EXCLUDED.verified,
    last_synced_at     = EXCLUDED.last_synced_at,
    updated_at         = EXCLUDED.updated_at;
SQL

    # ---- Related tables ----
    for t in poi_source_data poi_feedback preference_feedback; do
        if [ -f "$dir/$t.data.sql" ]; then
            echo "== Importing $t (clear + reload) =="
            run_tgt -c "DELETE FROM $t;"
            run_tgt -f "$dir/$t.data.sql"
        fi
    done

    run_tgt -c "DROP TABLE IF EXISTS sync_stage_poi;"

    echo
    echo "== Import summary =="
    run_tgt -c "SELECT COUNT(*) AS total_pois FROM poi;"
    run_tgt -c "SELECT COUNT(*) AS tripadvisor_hotels FROM poi WHERE osm_type='T';"
}

case "${1:-}" in
    export)
        since=""; full=0; shift
        while [ $# -gt 0 ]; do
            case "$1" in
                --since) since="$2"; shift 2;;
                --full)  full=1; shift;;
                *) usage;;
            esac
        done
        do_export "$since" "$full"
        ;;
    import)
        do_import "${2:-exports}"
        ;;
    *) usage;;
esac
