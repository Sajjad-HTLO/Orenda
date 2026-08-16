# Orenda — AI Travel Planner (Turkey)

A personalized AI travel planning platform — a "Travel Operating System" for Turkey — built with Spring Boot 4. It
ingests OpenStreetMap POI data, exposes a spatial search API, integrates free third-party services for weather and
routing, and turns a traveler's profile, preferences, and constraints into a personalized, explained itinerary.

**Mobile apps (iOS + Android)** are built with **Flutter** — one Dart codebase compiled to both platforms, talking to
this backend over REST.

---

## What's built

| Area                          | Status  | Details                                                                                                                     |
|-------------------------------|---------|-----------------------------------------------------------------------------------------------------------------------------|
| OSM POI importer              | Done    | Spring Batch job reads `turkey-tourist.osm.pbf` (~43 K tourist POIs) and bulk-inserts into PostGIS                          |
| POI search API                | Done    | Nearby search (radius + category), full-text search, single-POI lookup, category listing                                    |
| Weather API                   | Done    | Current conditions + 1–16 day forecast via Open-Meteo (free, no key)                                                        |
| Routing API                   | Done    | Point-to-point routing via OSRM public server (free, no key); returns distance, duration, GeoJSON geometry                  |
| POI feedback                  | Done    | Users report closed/inaccurate/moved POIs → data updates immediately + an alternative POI is suggested                      |
| Wikipedia enrichment          | Done    | Scheduled Spring Batch job adds summaries/images/descriptions to POIs (rate-limited, no key)                                |
| Trip planner pipeline         | Done    | `POST /api/trips/plan`: candidates → filter → score → opening hours → weather → travel time → walking/budget → optimization |
| Itinerary optimization        | Done    | Timed day-by-day plan: nearest-neighbour ordering, daily walking/travel budgets, lunch/rain/opening notes                   |
| AI itinerary narrative        | Done    | Natural-language explanation of each day, each stop's "why", and every feedback-driven adjustment                           |
| Onboarding / traveler profile | Done    | Short profile (5–8 questions) with long-term interests + per-trip overrides                                                 |
| Preference learning           | Done    | Reactions (like/dislike/love/not-interested/rate) → per-category weights + "I noticed you tend to prefer…" insight          |
| Feedback → constraints        | Done    | "too expensive / too far / too crowded / not suitable for kids / prefer quieter" shape the next plan                        |
| Personalized enrichment       | Done    | `POST /api/preferences/{sessionId}/enrich` enriches the traveler's favorite POI categories first                            |
| Crawler stop-event log        | Done    | Every block/captcha/stop during TripAdvisor crawling is appended to `data/tripadvisor-crawl-stops.csv`                      |
| Mobile app                    | Planned | iOS + Android apps in **Flutter** (single Dart codebase) — roadmap in [`MILESTONES.md`](MILESTONES.md)                      |

---

## Tech stack

### Backend

- **Java 21**, **Spring Boot 4.0.6**
- **Spring Batch** — OSM import + Wikipedia enrichment pipelines
- **Spring JDBC** — bulk PostGIS inserts
- **Spring Web / RestClient** — REST API + outbound HTTP to external services
- **PostgreSQL + PostGIS** — spatial storage
- **Flyway** — schema migrations
- **osm4j** — `.osm.pbf` parsing
- **Playwright** — TripAdvisor crawler (persistent Chrome profile, stealth, DataDome handling)
- **Jackson** — JSON / JSONB serialization

### Mobile (planned)

- **Flutter** (Dart) — single codebase for **iOS + Android**
- **dio / retrofit** — REST client for this backend
- **Riverpod or BLoC** — state management
- **flutter_map** — map display for POIs and routes
- **dart_openai / langchain_dart** — AI assistant features

---

## Prerequisites

- JDK 21+
- [Docker](https://www.docker.com/) (to run the `aitp-pg` PostGIS container)
- Database `aitp` accessible at `localhost:5432` (user `postgres`, password `postgres`) — provided by the `aitp-pg` container below

---

## Database container (`aitp-pg`)

The app expects a PostgreSQL + PostGIS instance at `localhost:5432`, with database `aitp` and credentials
`postgres` / `postgres`, running in a Docker container named `aitp-pg`. The container also holds the
PostGIS extensions (`postgis`, `uuid-ossp`) required by the `poi` schema.

### Create the container

```bash
# 1. Pull the PostGIS-enabled PostgreSQL image
docker pull postgis/postgis:16-3.4

# 2. Create a named volume so data survives container recreation
docker volume create aitp-pg-data

# 3. Create and start the container
docker run -d \
  --name aitp-pg \
  --restart=unless-stopped \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD='SajadShahin200200@1253##' \
  -e POSTGRES_DB=aitp \
  -p 127.0.0.1:5432:5432 \
  -v aitp-pg-data:/var/lib/postgresql/data \
  postgis/postgis:16-3.4
```

> **Image tags:** `postgis/postgis:16-3.4` bundles PostgreSQL 16 + PostGIS 3.4. The app requires
> PostgreSQL 15+, so `15-3.4`, `16-3.5`, `17-3.5`, … also work — just swap the tag in the commands above.
>
> **Port conflict:** if `5432` is already taken on your host, map a different host port (e.g.
> `-p 5433:5432`) and update `spring.datasource.url` in
> [`src/main/resources/application.properties`](src/main/resources/application.properties:5) to match.

### Verify the container

```bash
docker ps --filter name=aitp-pg                       # STATUS should show "Up"
docker exec aitp-pg psql -U postgres -d aitp -c "SELECT PostGIS_Full_Version();"
docker exec aitp-pg psql -U postgres -d aitp -c "SELECT current_database(), current_user;"
```

The `poi` table and the PostGIS extensions are created automatically by Flyway the first time the app
starts (`./mvnw spring-boot:run`).

### Day-to-day management

```bash
docker start aitp-pg        # start the container (e.g. after a reboot / `docker stop`)
docker stop aitp-pg         # stop it
docker logs -f aitp-pg      # tail container logs
```

### Reset / recreate from scratch

```bash
docker stop aitp-pg
docker rm aitp-pg                    # remove the container only (named volume keeps the data)
docker volume rm aitp-pg-data        # ALSO delete all data — only if you want a clean slate
# then re-run the `docker run` command from "Create the container" above
```

> Keeping data in the named volume `aitp-pg-data` means you can `docker rm` / `docker run` the container
> freely (e.g. to upgrade the image tag) without losing your imported POIs.

---

## Running

```bash
# Build
./mvnw clean package

# Start the API server (OSM import disabled by default)
./mvnw spring-boot:run

# Trigger a one-time OSM import (needs the .pbf file in data/)
./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-Dspring.batch.job.enabled=true"
```

Sanity-check the imported data:

```bash
docker exec aitp-pg psql -U postgres -d aitp -c "SELECT COUNT(*) AS total_pois, COUNT(*) FILTER (WHERE 'wikipedia' =
ANY(data_sources)) AS wikipedia_enriched, COUNT(*) FILTER (WHERE 'wikipedia' != ALL(data_sources)) AS not_yet_enriched
FROM poi;"
```

## Dump the database

```bash
docker exec -t aitp-pg pg_dump -U postgres -d aitp -Fc -f /tmp/db_dump.dump
docker cp aitp-pg:/tmp/db_dump.dump ./db_dump.dump
```

---

## API reference

### POIs

```
GET /api/pois/nearby?lat=41.01&lon=28.97&radiusKm=5&category=historic
GET /api/pois/{id}
GET /api/pois/search?q=topkapi
GET /api/pois/categories
```

> `nearby` and `search` exclude POIs that users have reported as **closed** or **duplicate** (see feedback below).
> Inaccurate / moved POIs stay in results but are flagged for review.

### POI feedback — immediate user reports

```
POST /api/pois/feedback
```

When a suggested POI is closed, wrong, or no longer valid, the user can report it and the app updates the POI data
immediately, then returns the nearest alternative POI in the same response.

Request body:

```json
{
  "poiId": "3f9b2c1a-…-uuid",
  "type": "CLOSED",
  "details": "Permanently closed",
  "sessionId": "optional-client-session-id",
  "newLat": 41.0052,
  "newLon": 28.9772
}
```

`type` is one of:

| Type         | Effect on the POI                                                                                 |
|--------------|---------------------------------------------------------------------------------------------------|
| `CLOSED`     | Marked as closed, score lowered, **excluded** from future results                                 |
| `DUPLICATE`  | Marked as duplicate, score lowered, **excluded** from future results                              |
| `INACCURATE` | Flagged `needs_review`, score lowered, still shown                                                |
| `MOVED`      | Flagged `needs_review`, score lowered, still shown; `newLat`/`newLon` correct the stored location |
| `OTHER`      | Flagged `needs_review`, score lowered, still shown                                                |

Response:

```json
{
  "accepted": true,
  "message": "Feedback received — POI marked as closed and excluded from future results.",
  "alternativePoi": {
    "id": "…",
    "nameTr": "…",
    "category": "historic",
    "lat": 41.011,
    "lon": 28.972
  }
}
```

`alternativePoi` is the nearest non-excluded POI near the reported one (same category when available) and may be
`null` when nothing is in range. Status codes: `200` accepted, `400` missing/invalid fields, `404` unknown `poiId`.

### Weather — Open-Meteo (free, no API key)

```
GET /api/weather?lat=41.01&lon=28.97&days=7
```

Response:

```json
{
  "location": { "latitude": 41.01, "longitude": 28.97, "timezone": "Europe/Istanbul" },
  "current": {
    "temperature": 28.4,
    "feelsLike": 29.1,
    "humidity": 62,
    "windSpeed": 14.5,
    "windDirection": 220,
    "weatherCode": 2,
    "description": "Partly cloudy"
  },
  "daily": [
    {
      "date": "2026-07-01",
      "maxTemp": 32.1,
      "minTemp": 22.3,
      "precipitation": 0.0,
      "maxWindSpeed": 18.2,
      "weatherCode": 0,
      "description": "Clear sky"
    }
  ]
}
```

`days` range: 1–16 (default 7).

### Routing — OSRM public demo server (free, no API key)

```
GET /api/route?fromLat=41.01&fromLon=28.97&toLat=36.89&toLon=30.71&profile=driving
```

`profile`: `driving` (default) | `foot` | `bike`

Response:

```json
{
  "distanceKm": 734.21,
  "durationMinutes": 428.5,
  "profile": "driving",
  "summary": "D400, D650",
  "geometry": {
    "type": "LineString",
    "coordinates": [[28.97, 41.01], ["..."]]
  }
}
```

Geometry is a GeoJSON `LineString` ready to render on a map.

### Trip planning — personalized itinerary

```
POST /api/trips/plan
```

The core planner. Accepts the traveler's questionnaire (basics, profile, interests, travel style) and optionally a
`sessionId` to blend in learned preferences and constraints. Runs the full pipeline:

```
Candidate POIs → Filter → Score → Opening hours → Weather → Travel time
  → Walking constraints → Budget → Optimization → AI-generated itinerary
```

Request body (abbreviated):

```json
{
  "basics": {
    "destination": "Istanbul",
    "startDate": "2026-08-15",
    "endDate": "2026-08-18",
    "travelerCount": 2,
    "childrenCount": 1,
    "accommodationLocation": "41.0082,28.9784",
    "transportMode": "FOOT"
  },
  "profile": {
    "ageRange": "AGE_25_34",
    "groupType": "FAMILY",
    "childAgeRanges": ["UNDER_18"],
    "mobilityLimitation": "NONE"
  },
  "interests": {
    "selectedInterests": ["HISTORY", "MUSEUMS", "FOOD"],
    "additionalNotes": "prefer mornings"
  },
  "style": {
    "pace": "BALANCED",
    "walking": "MODERATE",
    "budget": "MID_RANGE",
    "food": "LOCAL",
    "planningStyle": "DETAILED_SCHEDULE"
  },
  "sessionId": "optional-client-session-id"
}
```

Response:

```json
{
  "tripDays": 4,
  "summary": "Found 12 suggestions for your 4-day family trip to Istanbul. Top match: …",
  "weatherSummary": "AUG 15: Slight rain, 20°C · AUG 16: Clear sky, 26°C",
  "preferenceInsight": "I noticed you tend to prefer cultural experiences. I've adjusted your recommendations accordingly.",
  "narrative": "I've planned a family trip to Istanbul at a balanced pace… A few adjustments based on your feedback: …",
  "suggestions": [
    {
      "poi": { "id": "…", "nameTr": "Topkapı Palace", "category": "historic", "lat": 41.01, "lon": 28.98 },
      "score": 81.5,
      "factors": { "interest_match": 25.0, "opening_hours": 10.0, "weather": 10.0, "preference": 9.2, "…": 0 },
      "reasons": ["Matches your interest in history", "Open during your trip", "12 min from your base"],
      "travelMinutes": 12,
      "visitMinutes": 120
    }
  ],
  "dayPlan": [
    {
      "day": 1,
      "date": "2026-08-15",
      "weather": "Slight rain, 20°C",
      "items": [
        {
          "poi": { "id": "…", "nameTr": "Topkapı Palace", "category": "historic" },
          "startTime": "09:40",
          "endTime": "11:40",
          "travelMinutes": 12,
          "visitMinutes": 120,
          "openAtScheduledTime": true,
          "reasons": ["Matches your interest in history"]
        }
      ],
      "notes": ["Lunch break recommended around 13:00."],
      "narrative": "Day 1 · Saturday, Aug 15 — Slight rain, 20°C, so today leans indoor. - Topkapı Palace at 09:40…"
    }
  ],
  "notes": ["Few results — consider expanding interests or travel radius."]
}
```

The pipeline stages are intentionally transparent: every POI carries a score, a per-factor breakdown (`factors`), and
human-readable `reasons`; each day carries a `narrative`, weather, timing, and advice notes. The `narrative`
field is the "AI layer" — it explains *why* each stop was chosen and what was adjusted because of the traveler's
feedback, rather than inventing travel data.

`planningStyle`: `DETAILED_SCHEDULE` (day-by-day) | `RECOMMENDATIONS_ONLY` (ranked list only) | `SURPRISE_ME`
(mildly randomized).

### Preferences — learning from feedback

The system learns per-category weights (culture, food, shopping, nightlife, …) from the traveler's reactions, so it can
rank what this specific person actually enjoys.

```
GET  /api/preferences/{sessionId}   # current per-category weights (0..1)
POST /api/preferences/feedback      # record a reaction (+ optional reason)
POST /api/preferences/{sessionId}/enrich   # enrich the traveler's favorite categories first
```

`POST /api/preferences/feedback` request:

```json
{
  "poiId": "3f9b2c1a-…-uuid",
  "sessionId": "optional-client-session-id",
  "reaction": "LOVE",
  "rating": 5,
  "reason": "FIND_SIMILAR"
}
```

`reaction`: `LIKE` | `DISLIKE` | `LOVE` | `NOT_INTERESTED` | `RATED` (rating 1–5 when `RATED`).

`reason` (optional): `TOO_EXPENSIVE` | `TOO_FAR` | `TOO_CROWDED` | `NOT_SUITABLE_FOR_KIDS` | `PREFER_QUIETER` |
`FIND_SIMILAR`.

Response:

```json
{
  "accepted": true,
  "message": "Noted — adding more places like this to your recommendations.",
  "updatedWeights": { "CULTURE": 0.91, "FOOD": 0.83, "SHOPPING": 0.21, "NIGHTLIFE": 0.08, "…": 0.5 },
  "insight": "I noticed you tend to prefer cultural experiences and local food. You seem to skip nightlife and shopping. I've adjusted your recommendations accordingly.",
  "similarPois": [ "…" ]
}
```

`similarPois` is returned only for `FIND_SIMILAR`. Weights are updated with an exponential moving average, so recent
feedback counts more than old feedback.

### Feedback reasons → planning constraints

Besides nudging weights, the reasons shape the *next* plan:

| Reason                  | Effect on the next plan                                                       |
|-------------------------|-------------------------------------------------------------------------------|
| `TOO_EXPENSIVE`         | Caps the effective budget one tier below the current cap                      |
| `TOO_FAR`               | Shrinks the search radius (8 km first, then −30%)                             |
| `TOO_CROWDED`           | De-prioritizes popular venues (by Tripadvisor `review_count`)                 |
| `NOT_SUITABLE_FOR_KIDS` | **Excludes** adult-oriented venues (nightclubs, casinos, bars, `adults_only`) |
| `PREFER_QUIETER`        | De-ranks nightlife/entertainment, boosts parks/gardens/cafés                  |
| `FIND_SIMILAR`          | Returns same-category POIs nearby (no constraint)                             |

These adjustments are explained in the plan's `narrative` and surfaced in `notes`.

### Traveler profile — short onboarding

```
POST /api/profile
GET  /api/profile/{sessionId}
```

The long-term traveler profile (≈5–8 onboarding questions). A trip can override any field via `TripPlanRequest`; when a
trip sends an empty `selectedInterests` list, the planner falls back to the profile's baseline interests.

```json
{
  "sessionId": "optional-client-session-id",
  "travelerCount": 2,
  "childrenCount": 1,
  "interests": ["HISTORY", "MUSEUMS", "FOOD", "LOCAL_CULTURE"],
  "groupType": "FAMILY",
  "ageRange": "AGE_25_34",
  "mobility": "NONE",
  "pace": "BALANCED",
  "budget": "MID_RANGE",
  "walking": "MODERATE",
  "food": "LOCAL"
}
```

### Wikipedia enrichment — personalized

The enrichment job normally enriches the least-complete POIs first. When launched with a session
(`POST /api/preferences/{sessionId}/enrich`), it instead prioritizes the traveler's **loved categories**
(`user_preference.weight` order), so the data improves exactly where the traveler looks. Returns `503` when enrichment
is disabled (`wikipedia.enrichment.enabled=false`).

---

## DB schema (abbreviated)

```sql
poi (id UUID PK, osm_id BIGINT, name_tr TEXT, name_en TEXT,
     category TEXT, subcategory TEXT,
     location GEOGRAPHY(POINT,4326),
     boundary GEOGRAPHY(POLYGON,4326),
     completeness_score SMALLINT,
     attributes JSONB,          -- opening_hours, phone, website, cuisine, wikidata, rating, …
     data_sources TEXT[],
     verified BOOLEAN,
     last_synced_at, created_at, updated_at)

poi_source_data          (per-source field tracking)
poi_feedback             (POI data-quality reports: CLOSED/INACCURATE/MOVED/DUPLICATE/OTHER)
tripadvisor_crawled_pages (crawler pagination progress/resume state)

traveler_profile         (long-term profile from onboarding: interests, pace, budget, walking, …)
user_preference          (learned per-category weights 0..1: CULTURE, FOOD, SHOPPING, NIGHTLIFE, …)
preference_feedback      (reaction events: LIKE/DISLIKE/LOVE/NOT_INTERESTED/RATED + reasons)
preference_constraint    (plan constraints derived from reasons: BUDGET_CAP, MAX_RADIUS_KM, …)
```

Full migrations: `src/main/resources/db/migration/V1__create_poi_schema.sql` …
`V11__create_preference_constraint_table.sql`

---

## Mobile apps (Flutter)

**Decision (Aug 2026):** Flutter was chosen over React Native and Kotlin Multiplatform for 100% code sharing across
iOS + Android, native performance, and clean Dart syntax that suits AI-agent-assisted development.

The mobile apps talk to this backend over REST and will expose:

- **POI search & discovery** — nearby (radius + category), full-text (TR/EN), detail view with map
- **Weather** — current conditions + forecast for any POI location
- **Routing** — point-to-point routes (driving / foot / bike) with GeoJSON rendered on the map
- **Short onboarding** — 5–8 questions → long-term traveler profile
- **Personalized trip planning** — one request → ranked suggestions, timed day-by-day itinerary, weather-aware ordering
- **Immediate feedback** — 👍/👎/❤️/❌/⭐ + reasons; the next plan (and enrichment) adjusts to the traveler
- **AI assistant** — the itinerary narrative explains each day, each stop's "why", and every adjustment

Planned structure, dependencies, API contract, and the phased roadmap (Setup → POIs → Weather → Routing → AI →
Advanced → Polish/Release) live in [`MILESTONES.md`](MILESTONES.md).

---

## External services used

| Service                                    | Purpose                          | Cost                   | Key required |
|--------------------------------------------|----------------------------------|------------------------|--------------|
| [Open-Meteo](https://open-meteo.com)       | Weather forecast                 | Free                   | No           |
| [OSRM](https://project-osrm.org)           | Turn-by-turn routing             | Free (demo server)     | No           |
| [Wikipedia](https://en.wikipedia.org)      | POI summaries/images             | Free                   | No           |
| [TripAdvisor](https://www.tripadvisor.com) | Hotel listings/details (crawler) | Free (no official API) | No           |
| OpenStreetMap                              | POI dataset (`.osm.pbf`)         | Free (ODbL)            | No           |
