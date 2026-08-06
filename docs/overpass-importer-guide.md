# Overpass API Importer — Run Guide

## Overview

The Overpass importer fetches tourist POIs from the **Overpass API** (`https://overpass-api.de/api/interpreter`), a
free, no-auth read-only API that queries OpenStreetMap data. It runs 75 tag-based category queries within Turkey's
bounding box and batch-inserts/updates results into the `poi` table.

**Key characteristics:**

- **Free** — no API key, no authentication required
- **75 category queries** covering tourism, historic, amenity, natural, leisure, building, and more
- **~90,000 POIs** fetched per full run (varies by OSM data freshness)
- **~36 minutes** per full run (with 5s inter-query delay)
- **Rate limited** — the public Overpass API may return 429/504 errors; retry logic handles most

---

## How to Run

### Option 1: REST API (Recommended for manual runs)

```bash
# Trigger the import manually
curl -X POST http://localhost:8080/api/overpass/import
```

Response:

```json
{"status": "STARTED", "message": "Overpass import job launched successfully"}
```

Monitor progress via application logs. Look for:

```
╔══════════════════════════════════════════════════════════════╗
║  OVERPASS API IMPORT — 75 categories to fetch
╚══════════════════════════════════════════════════════════════╝
┌─ [1/75] tag=tourism=museum  →  culture/museum
└─ [1/75] ✅ 1000 POIs  │  running total: 1000
...
╔══════════════════════════════════════════════════════════════╗
║  OVERPASS FETCH COMPLETE: 90542 total POIs across 75 categories
╚══════════════════════════════════════════════════════════════╝
Job: [overpassImportJob] completed with status: COMPLETED
```

### Option 2: Automatic Schedule

The importer runs automatically on startup (initial delay = 0) and then weekly (every 7 days). Configured in
`application.properties`:

```properties
overpass.import.enabled=true
overpass.import.initial-delay-ms=0
overpass.import.fixed-delay-ms=604800000
```

### Option 3: Disable and Run Manually

To disable the automatic schedule and only run manually:

```properties
overpass.import.enabled=true
overpass.import.initial-delay-ms=999999999   # effectively never
overpass.import.fixed-delay-ms=999999999     # effectively never
```

Then trigger via REST:

```bash
curl -X POST http://localhost:8080/api/overpass/import
```

---

## Configuration Reference

| Property                                | Default     | Description                            |
|-----------------------------------------|-------------|----------------------------------------|
| `overpass.import.enabled`               | `true`      | Enable/disable the Overpass import job |
| `overpass.import.chunk-size`            | `100`       | Batch chunk size for DB writes         |
| `overpass.import.initial-delay-ms`      | `0`         | Delay before first scheduled run (ms)  |
| `overpass.import.fixed-delay-ms`        | `604800000` | Delay between scheduled runs (7 days)  |
| `overpass.import.query-timeout-seconds` | `180`       | Timeout per Overpass query             |

---

## Database Impact

The importer uses **UPSERT** on `(osm_id, osm_type)`. Since Overpass returns real OSM elements (N/W/R), these may
collide with existing POIs from the PBF import. On conflict:

- `name_tr`, `category`, `subcategory`, `location` are updated
- `attributes` are merged via `||` (JSONB concatenation)
- `completeness_score` takes the maximum
- `data_sources` is set to `ARRAY['overpass']`

**Check results:**

```sql
-- Total POI count
SELECT count(*) FROM poi;

-- Breakdown by data source
SELECT data_sources, count(*) FROM poi GROUP BY data_sources ORDER BY count(*) DESC;

-- Overpass POIs by category
SELECT category, count(*) FROM poi
WHERE 'overpass' = ANY(data_sources)
GROUP BY category ORDER BY count(*) DESC;
```

---

## Categories Queried

| Tag Group | Examples                                                                                                                                                                                                                                             | Count |
|-----------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------|
| Tourism   | museum, gallery, artwork, aquarium, attraction, viewpoint, zoo, theme_park, picnic_site                                                                                                                                                              | 9     |
| Historic  | castle, ruins, monument, memorial, archaeological_site, fort, palace, amphitheatre, aqueduct, tower, mausoleum, caravanserai, church, mosque, synagogue, bridge, lighthouse, battlefield, city_gate, citywalls, obelisk, tomb, wayside_shrine, wreck | 24    |
| Amenity   | place_of_worship, theatre, arts_centre, library, cinema, nightclub, marketplace, bazaar, spa                                                                                                                                                         | 9     |
| Natural   | beach, cave_entrance, volcano, hot_spring, spring, peak, valley, waterfall                                                                                                                                                                           | 8     |
| Leisure   | park, garden, nature_reserve, marina, stadium, water_park, beach_resort                                                                                                                                                                              | 7     |
| Building  | mosque, church, synagogue, cathedral, temple                                                                                                                                                                                                         | 5     |
| Man-made  | lighthouse, tower, watermill, windmill, obelisk                                                                                                                                                                                                      | 5     |
| Other     | national_park, nature_reserve, tourist_railway, shop=mall, aeroway=terminal, religion=place_of_worship, waterway=waterfall                                                                                                                           | 8     |

---

## Troubleshooting

### 429 Too Many Requests / 504 Gateway Timeout

The public Overpass API rate-limits aggressively. The importer retries 3 times with exponential backoff (6s, 12s). If
queries still fail:

1. **Increase inter-query delay** — edit `OverpassItemReader.java` line 86: change `Thread.sleep(5_000)` to
   `Thread.sleep(10_000)` or `15_000`
2. **Run overnight** — the API is less busy during European night hours
3. **Use a mirror** — Overpass has community mirrors that may be less busy
4. **Self-host** — install your own Overpass instance for unrestricted access

### Job doesn't start

Check that `overpass.import.enabled=true` in `application.properties` and that the app has started successfully.

### No new POIs appear

Overpass POIs use real OSM IDs (N/W/R). If you already imported the same OSM data via PBF, the UPSERT will update
existing rows rather than insert new ones. Check `data_sources` — existing POIs will have `{osm}` while Overpass-only
POIs will have `{overpass}`.