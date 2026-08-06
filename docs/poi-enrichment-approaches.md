# Free Approaches to Enrich Turkey Tourist POI Database

> Analysis for AITP project — July 2026

## Current Enrichment Pipeline

| Source              | What It Provides                                  | Mechanism                                              |
|---------------------|---------------------------------------------------|--------------------------------------------------------|
| **OSM PBF**         | Base POI layer (nodes, ways, relations)           | Batch import from `turkey-travel-named.osm.pbf`        |
| **Wikidata SPARQL** | ~40 categories of tourist POIs with TR/EN labels  | `WikidataSparqlReader` queries via `WikidataClient`    |
| **Wikipedia API**   | Summaries, descriptions, images for existing POIs | `WikipediaEnrichmentProcessor` enriches by name search |
| **muze.gov.tr**     | Turkish museums & archaeological sites            | `MuzeGovTrClient` scrapes GeoJSON-like data            |

---

## Recommended Free Approaches (Prioritized)

### 1. ⭐ Overpass API — Targeted OSM Queries

**Why:** PBF import is a bulk snapshot. Overpass lets you run surgical queries for specific tags, regions, or time-based
diffs that the PBF might miss or that have been added since.

**What you'd get:**

- POIs with specific Turkish tourism tags (`tourism=museum`, `historic=*`, `place_of_worship=mosque`, etc.)
- Recently changed POIs (using `diff` queries)
- POIs in specific Turkish provinces/regions
- Relations (route relations for hiking trails, etc.) that PBF tools sometimes skip

**Endpoint:** `https://overpass-api.de/api/interpreter` (public, no auth, no key)

**Rate limit:** ~10,000 requests/day, reasonable query complexity limits

**Example query for museums in İstanbul:**

```
[out:json];
area["name"="İstanbul"]["admin_level"="4"]->.istanbul;
node["tourism"="museum"](area.istanbul);
out body;
```

**Integration effort:** Low — REST API with JSON responses. ✅ **IMPLEMENTED**

---

### 2. ⭐ Wikivoyage — Structured Travel Guides (Wikimedia)

**Why:** Wikivoyage is Wikimedia's travel guide. It has structured listings for "See", "Do", "Buy", "Eat", "Drink", "
Sleep" sections with coordinates, descriptions, and hours — all CC-BY-SA licensed.

**What you'd get:**

- Curated, human-written descriptions (better than raw OSM tags)
- Opening hours, admission fees, contact info
- "Star" rated destinations (Wikivoyage's quality grading)
- Already organized by destination (city/region)

**Endpoint:** `https://en.wikivoyage.org/w/api.php` (MediaWiki API, free, no auth)

**Integration effort:** Medium — requires HTML/template parsing.

---

### 3. ⭐ UNESCO World Heritage List — High-Value Sites

**Why:** Turkey has 21 UNESCO World Heritage sites (e.g., Göreme, Ephesus, Pamukkale, Troy) and 79+ tentative sites.

**What you'd get:**

- Official names in multiple languages
- Precise coordinates and boundary polygons
- Detailed descriptions, criteria, year of inscription

**Better approach — SPARQL query via existing Wikidata pipeline:**

```sparql
SELECT ?item ?itemLabel ?itemLabelTr ?coord ?unescoId WHERE {
  ?item wdt:P757 ?unescoId;
        wdt:P17 wd:Q43;
        wdt:P625 ?coord.
  SERVICE wikibase:label { bd:serviceParam wikibase:language "en,tr". }
}
```

**Integration effort:** Very low — add a `cat()` entry to existing Wikidata queries.

---

### 4. ⭐ GeoNames — Free Geographical Database

**Why:** GeoNames has ~25 million geographical names worldwide, including Turkish places with feature codes for
tourist-relevant categories.

**What you'd get:**

- Alternative name spellings (useful for Turkish diacritics)
- Population data for settlements
- Feature classification
- Elevation data

**Endpoint:** `http://api.geonames.org/` — requires free registration for a username

**Relevant feature classes for Turkey tourism:**

- `S.MUS` — museums
- `S.HTL` — hotels
- `S.CH` — churches
- `S.MSQE` — mosques
- `S.TOWR` — towers
- `L.PRK` — parks
- `T.PK` — peaks

**Rate limit:** 2,000 credits/hour (free), 30,000 credits/day

**Integration effort:** Low — REST API.

---

### 5. OpenTripMap — Tourist Attraction API

**Why:** Purpose-built API for tourist attractions. Aggregates OSM + Wikidata + user contributions.

**What you'd get:**

- Tourist attraction names, descriptions, images
- Categorized by `kinds` (museums, natural, historic, architecture, etc.)
- Already filtered for tourism relevance
- Rating and popularity signals

**Endpoint:** `https://api.opentripmap.com/0.1/en/places/` — free tier: 5,000 requests/day

**Integration effort:** Low — REST API with JSON responses.

---

### 6. Turkish Government Open Data

**Potential sources:**

| Source                              | What It May Have                                       | Status                         |
|-------------------------------------|--------------------------------------------------------|--------------------------------|
| **acikveri.gov.tr**                 | Government datasets including cultural sites           | Needs exploration              |
| **Kültür Envanteri**                | Registered cultural properties, archaeological sites   | May require scraping           |
| **TÜİK**                            | Regional tourism statistics, registered establishments | Free but may need registration |
| **T.C. Kültür ve Turizm Bakanlığı** | Hotel registrations, certified tourism businesses      | May have open data portal      |

**Integration effort:** High — requires research.

---

### 7. Expand Wikidata SPARQL Queries

**Missing categories to consider:**

- `Q570116` — ski resorts (Uludağ, Palandöken, etc.)
- `Q1081138` — vineyards/wineries (wine tourism)
- `Q131681` — ancient Greek cities (Ephesus, Miletus, Pergamon)
- `Q9259` — UNESCO World Heritage Sites
- `Q16917` — hospitals (medical tourism)

**Integration effort:** Very low — just add `cat()` entries.

---

### 8. Hiking/Outdoor Data Sources

| Source               | What                        | Endpoint                                  |
|----------------------|-----------------------------|-------------------------------------------|
| **OpenTrailMap**     | Hiking trails from OSM      | Free tiles/API                            |
| **Waymarked Trails** | Hiking, cycling, MTB routes | `https://hiking.waymarkedtrails.org/api/` |

**Integration effort:** Medium — niche but valuable for adventure tourism.

---

## Priority Matrix

| Approach                    | Value   | Effort   | Data Quality   | Status          |
|-----------------------------|---------|----------|----------------|-----------------|
| **Overpass API**            | High    | Low      | High (OSM)     | ✅ Implemented   |
| **Wikivoyage**              | High    | Medium   | High (curated) | Not started     |
| **UNESCO via Wikidata**     | Medium  | Very Low | Very High      | Not started     |
| **GeoNames**                | Medium  | Low      | Medium         | Not started     |
| **OpenTripMap**             | Medium  | Low      | Medium         | Not started     |
| **Expand Wikidata queries** | Medium  | Very Low | High           | Not started     |
| **Turkish Gov Open Data**   | Unknown | High     | Variable       | Research needed |
| **Outdoor trail APIs**      | Niche   | Medium   | Medium         | Not started     |