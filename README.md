# Orenda — AI Travel Planner (Turkey)

A backend "Travel Operating System" for Turkey, built with Spring Boot 4. It ingests OpenStreetMap POI data, exposes a
spatial search API, and integrates free third-party services for weather and routing.

---

## What's built

| Area             | Status | Details                                                                                                    |
|------------------|--------|------------------------------------------------------------------------------------------------------------|
| OSM POI importer | Done   | Spring Batch job reads `turkey-tourist.osm.pbf` (~43 K tourist POIs) and bulk-inserts into PostGIS         |
| POI search API   | Done   | Nearby search (radius + category), full-text search, single-POI lookup, category listing                   |
| Weather API      | Done   | Current conditions + 7-day forecast via Open-Meteo (free, no key)                                          |
| Routing API      | Done   | Point-to-point routing via OSRM public server (free, no key); returns distance, duration, GeoJSON geometry |

---

## Tech stack

- **Java 21**, **Spring Boot 4.0.6**
- **Spring Batch** — OSM import pipeline
- **Spring JDBC** — bulk PostGIS inserts
- **Spring Web / RestClient** — REST API + outbound HTTP to external services
- **PostgreSQL + PostGIS** — spatial storage
- **Flyway** — schema migrations
- **osm4j** — `.osm.pbf` parsing
- **Jackson** — JSON / JSONB serialization

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
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=aitp \
  -p 5432:5432 \
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

---

## DB schema (abbreviated)

```sql
poi (id UUID PK, osm_id BIGINT, name_tr TEXT, name_en TEXT,
     category TEXT, subcategory TEXT,
     location GEOGRAPHY(POINT,4326),
     boundary GEOGRAPHY(POLYGON,4326),
     completeness_score SMALLINT,
     attributes JSONB,          -- opening_hours, phone, website, cuisine, wikidata, …
     data_sources TEXT[],
     verified BOOLEAN,
     last_synced_at, created_at, updated_at)

poi_source_data (per-source field tracking)
```

Full migration: `src/main/resources/db/migration/V1__create_poi_schema.sql`

---

## External services used

| Service                              | Purpose                  | Cost               | Key required |
|--------------------------------------|--------------------------|--------------------|--------------|
| [Open-Meteo](https://open-meteo.com) | Weather forecast         | Free               | No           |
| [OSRM](https://project-osrm.org)     | Turn-by-turn routing     | Free (demo server) | No           |
| OpenStreetMap                        | POI dataset (`.osm.pbf`) | Free (ODbL)        | No           |
