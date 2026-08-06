#!/usr/bin/env python3
"""
Wikipedia Geosearch — Istanbul Tourist POI Importer

Scrapes all geo-tagged Wikipedia articles in Istanbul using the Wikipedia
geosearch API (free, no auth), filters to tourist-relevant keywords,
categorizes them, and inserts into the PostgreSQL poi table.

Usage:
    python3 import_istanbul_wiki_pois.py
"""

import urllib.request
import json
import time
import math
import subprocess
import sys

# ── Configuration ────────────────────────────────────────────────────────────

# Istanbul bounding box
MIN_LAT, MAX_LAT = 40.80, 41.28
MIN_LON, MAX_LON = 28.50, 29.46

# Grid: 10km radius circles with ~14km step (overlap to avoid gaps)
# 1° lat ≈ 111.32 km  →  step = 14/111.32 ≈ 0.126
# 1° lon ≈ 111.32 * cos(41°) ≈ 84.0 km → step = 14/84.0 ≈ 0.167
STEP_LAT = 14.0 / 111.32
STEP_LON = 14.0 / (111.32 * math.cos(math.radians(41.0)))

# Rate limiting
REQUEST_DELAY = 1.2  # seconds between requests (~50 req/min, safe for Wikipedia)
RETRY_DELAY = 5.0    # seconds after 429 errors

# Tourist keyword categorization
CATEGORIES = {
    ('culture', 'museum'): [
        'museum', 'müze', 'gallery', 'sanat'
    ],
    ('historic', 'place_of_worship'): [
        'mosque', 'cami', 'church', 'kilise', 'synagogue', 'sinagog',
        'hamam', 'bath', 'tomb', 'türbe', 'mescit'
    ],
    ('historic', 'archaeological_site'): [
        'palace', 'saray', 'castle', 'kale', 'fortress', 'hisar',
        'cistern', 'sarnıç', 'archaeological', 'arkeoloji', 'ancient', 'antik',
        'ruins', 'gate', 'kapı', 'wall', 'duvar', 'column', 'sütun', 'obelisk'
    ],
    ('historic', 'tower'): [
        'tower', 'kule', 'lighthouse', 'fener', 'bridge', 'köprü',
        'aqueduct', 'kemer'
    ],
    ('shopping', 'bazaar'): [
        'bazaar', 'çarşı', 'pazar', 'market', 'çarşısı', 'shopping', 'mall'
    ],
    ('nature', 'park'): [
        'park', 'garden', 'bahçe', 'square', 'meydan', 'monument', 'anıt',
        'fountain', 'çeşme'
    ],
    ('culture', 'theatre'): [
        'theatre', 'tiyatro', 'cinema', 'sinema', 'stadium', 'stadyum',
        'library', 'kütüphane'
    ],
    ('accommodation', 'hotel'): [
        'hotel', 'otel', 'caravanserai', 'han', 'restaurant', 'lokanta',
        'cafe', 'kahve', 'pavilion', 'köşk', 'mansion', 'konak'
    ],
    ('leisure', 'attraction'): [
        'pier', 'iskele', 'beach', 'plaj', 'island', 'ada',
        'aquarium', 'akvaryum', 'zoo'
    ],
    ('culture', 'university'): [
        'university', 'üniversite'
    ],
}


def classify(title):
    """Categorize a Wikipedia article title into (category, subcategory)."""
    t = title.lower()
    for (cat, sub), keywords in CATEGORIES.items():
        if any(kw in t for kw in keywords):
            return (cat, sub)
    return ('attraction', 'landmark')


def is_tourist(title):
    """Check if a Wikipedia title matches any tourist keyword."""
    t = title.lower()
    for keywords in CATEGORIES.values():
        if any(kw in t for kw in keywords):
            return True
    return False


# ── Step 1: Compute grid ─────────────────────────────────────────────────────

lats = []
lat = MIN_LAT
while lat <= MAX_LAT:
    lats.append(round(lat, 4))
    lat += STEP_LAT

lons = []
lon = MIN_LON
while lon <= MAX_LON:
    lons.append(round(lon, 4))
    lon += STEP_LON

grid = [(la, lo) for la in lats for lo in lons]
print(f"Grid: {len(lats)} × {len(lons)} = {len(grid)} points")
print(f"(lat range: {MIN_LAT}–{MAX_LAT}, lon range: {MIN_LON}–{MAX_LON})\n")

# ── Step 2: Fetch all Wikipedia articles via geosearch API ────────────────────

all_articles = {}  # pageid → {pageid, title, lat, lon}

for i, (la, lo) in enumerate(grid):
    url = (
        f"https://en.wikipedia.org/w/api.php"
        f"?action=query&list=geosearch"
        f"&gsradius=10000&gscoord={la}|{lo}"
        f"&gslimit=500&format=json"
    )

    for attempt in range(3):
        try:
            req = urllib.request.Request(
                url,
                headers={'User-Agent': 'AITP-TravelOS/0.1 (POI import; contact: dev@local)'}
            )
            data = json.load(urllib.request.urlopen(req, timeout=20))
            results = data.get('query', {}).get('geosearch', [])
            new_count = 0
            for r in results:
                pid = r['pageid']
                if pid not in all_articles:
                    all_articles[pid] = {
                        'pageid': pid,
                        'title': r['title'],
                        'lat': r['lat'],
                        'lon': r['lon']
                    }
                    new_count += 1
            print(f"  [{i+1:2d}/{len(grid)}] {la},{lo} → {len(results):3d} results "
                  f"(+{new_count} new, total unique: {len(all_articles)})")
            break  # success
        except urllib.error.HTTPError as e:
            if e.code == 429:
                print(f"  [{i+1:2d}/{len(grid)}] {la},{lo} → 429, retrying in {RETRY_DELAY}s...")
                time.sleep(RETRY_DELAY)
                RETRY_DELAY += 2  # exponential-ish backoff
            else:
                print(f"  [{i+1:2d}/{len(grid)}] {la},{lo} → HTTP {e.code}")
                break
        except Exception as e:
            print(f"  [{i+1:2d}/{len(grid)}] {la},{lo} → {e}")
            break

    time.sleep(REQUEST_DELAY)

print(f"\nTotal unique Wikipedia articles in Istanbul: {len(all_articles)}")

# ── Step 3: Filter and categorize ────────────────────────────────────────────

tourist_pois = []
for a in all_articles.values():
    if is_tourist(a['title']):
        cat, sub = classify(a['title'])
        a['category'] = cat
        a['subcategory'] = sub
        tourist_pois.append(a)

print(f"Tourist-relevant: {len(tourist_pois)} (filtered from {len(all_articles)})")

# Category breakdown
from collections import Counter
cats = Counter(f"{a['category']}/{a['subcategory']}" for a in tourist_pois)
print("\nCategory breakdown:")
for c, n in cats.most_common():
    print(f"  {c}: {n}")

# ── Step 4: Insert into PostgreSQL ───────────────────────────────────────────

print(f"\nInserting {len(tourist_pois)} POIs into database...")

# First ensure 'P' osm_type is allowed
subprocess.run(
    ['docker', 'exec', '-i', 'aitp-pg', 'psql', '-U', 'postgres', '-d', 'aitp', '-c',
     "ALTER TABLE poi DROP CONSTRAINT IF EXISTS poi_osm_type_check;"
     "ALTER TABLE poi ADD CONSTRAINT poi_osm_type_check CHECK (osm_type IN ('N','W','R','Q','G','P'));"],
    capture_output=True, text=True
)

inserts = []
for p in tourist_pois:
    osm_id = -abs(hash(p['title'])) % 999999999
    name_escaped = p['title'].replace("'", "''")
    attrs = json.dumps({
        'source': 'wiki_geosearch',
        'pageid': p['pageid'],
        'wikipedia_title': p['title'],
    }).replace("'", "''")

    insert = (
        f"INSERT INTO poi (osm_id, osm_type, name_tr, category, subcategory, "
        f"location, completeness_score, data_sources, attributes, verified) "
        f"VALUES ({osm_id}, 'P', '{name_escaped}', "
        f"'{p['category']}', '{p['subcategory']}', "
        f"ST_SetSRID(ST_MakePoint({p['lon']}, {p['lat']}), 4326)::geography, "
        f"50, ARRAY['wiki_geosearch']::text[], "
        f"'{attrs}'::jsonb, false) "
        f"ON CONFLICT (osm_id, osm_type) DO UPDATE SET "
        f"name_tr = EXCLUDED.name_tr, "
        f"category = EXCLUDED.category, "
        f"subcategory = EXCLUDED.subcategory, "
        f"location = EXCLUDED.location, "
        f"attributes = poi.attributes || EXCLUDED.attributes, "
        f"data_sources = ARRAY(SELECT DISTINCT unnest(poi.data_sources || EXCLUDED.data_sources)), "
        f"updated_at = NOW();"
    )
    inserts.append(insert)

sql = '\n'.join(inserts)
result = subprocess.run(
    ['docker', 'exec', '-i', 'aitp-pg', 'psql', '-U', 'postgres', '-d', 'aitp'],
    input=sql, capture_output=True, text=True, timeout=60
)

inserted = result.stdout.count('INSERT')
updated = result.stdout.count('UPDATE')
print(f"INSERTED: {inserted}, UPDATED: {updated}")

if result.stderr and 'ERROR' in result.stderr:
    print(f"ERRORS: {result.stderr[:800]}")
else:
    print("\nDone! Verify with:")
    print("docker exec aitp-pg psql -U postgres -d aitp -c \"SELECT osm_type, COUNT(*) FROM poi GROUP BY osm_type ORDER BY osm_type;\"")
