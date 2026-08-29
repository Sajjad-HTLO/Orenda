# Orenda — App Progress Report

_Generated: 2026-08-29_

## Verdict

Solid, feature-rich **backend PoC** (Spring Boot 4, Java 21). Compiles cleanly, ~120 tests in 22 classes, 9 API modules, 5 data-import jobs, a TripAdvisor crawler. **No frontend/mobile client yet** (Flutter is planned).

## Done & solid

| Area | Notes |
|---|---|
| **POI core + API** | Nearby radius search, full-text (TR/EN), by-id, categories — all PostGIS via `PoiRepository`. Well tested (unit + integration). |
| **POI feedback** | Closed/inaccurate/moved/duplicate/other → immediate data update + nearest alternative returned. Clean. |
| **Weather API** | Open-Meteo live fetch, 1–16 days, WMO code mapping. Tested. |
| **Routing API** | OSRM live, driving/foot/bike, GeoJSON geometry, haversine fallback. Tested. |
| **Trip planner** | **The crown jewel** — full pipeline implemented: candidates → filter → score (13 dimensions) → opening hours → live weather → OSRM travel time → walking/budget → optimizer → template narrative. ~63 tests incl. an E2E journey test. |
| **Preferences + profile** | EMA weight learning per category, insight text, FIND_SIMILAR, reasons→plan constraints, profile upsert/get, preference-prioritized enrichment trigger. 8 unit tests. |
| **Wikipedia enrichment** | Most elaborate job: partitioned/parallel, rate-limited, preference-aware ordering, stats listener. |
| **Schema** | 15 Flyway migrations covering poi, feedback, preferences, profile, constraints, users, poi_image. |

## Implemented but with real gaps

| Area | Gaps |
|---|---|
| **Auth** | Works (signup/verify/login/JWT/Google OAuth//me) but: **hardcoded Neon DB password + weak JWT secret committed in `application.properties`**, JWT leaked in redirect query string, `emailSent` always true, verification link points at raw JSON endpoint, no password reset / refresh tokens / logout / rate limiting, user enumeration via 409/404, dead 502 handler. Tested only at service level — no controller/integration tests. |
| **TripAdvisor crawler** | Hotels + restaurants + image download all implemented with DataDome stealth, resume, disk guard, stop-event CSV. But: **no `/api/tripadvisor/*` endpoint** — crawled data only surfaces through generic `/api/pois`; **images saved to disk but never servable** (no static handler); restaurant crawler lacks resume/stop-log machinery; `TripadvisorSchemaInitializer` is an empty placeholder; 3 empty marker config classes; 4× copy-pasted worker logic. |
| **Import jobs** (OSM/Wikidata/Overpass/MuzeGov/Wikipedia) | All implemented reader→processor→writer→config, most with schedulers + REST triggers. **All disabled by default** (`*=false`), **zero test coverage**. Specific dead code: Wikidata `wikipediaEnTitle` never populated; MuzeGov `descriptionTr` never extracted (max completeness 45/100); OSM no trigger besides startup + writer wipes `data_sources` on re-import; all trigger endpoints say `202` but block (sync `jobLauncher.run`); Wikipedia not-found POIs never retried. |

## Missing / next development

1. **Frontend / mobile client** — nothing exists. README plans Flutter (iOS+Android); `MILESTONES.md` is referenced but **doesn't exist in the repo**.
2. **TripAdvisor API + image serving** — expose crawled hotels/restaurants/images to clients (static resource handler or a file endpoint).
3. **Auth hardening** — move secrets to env vars, add refresh tokens/logout/password-reset, fix redirect token leak, frontend redirect after email verification.
4. **Cleanup / correctness** — unused request fields (`arrivalTime`, `departureTime`, `childrenCount`), `score` can exceed 100, snow not treated as rain in planner, `TravelTimeEstimator` has no test, preferences `NOT_INTERESTED` over-promises, constraints never expire, OSM/Prefs/Profile controllers untested.
5. **Import verification** — README's own next task ("Run OSM import against PostGIS and verify") still outstanding; local PostGIS (`aitp-pg`) is not running (app points at a Neon cloud DB).

## Security note (should fix first)

`src/main/resources/application.properties:7` contains a real Neon database password and `:121` a well-known JWT secret — both committed. Move to env vars before anything is deployed.