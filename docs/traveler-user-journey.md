# Orenda — Core Functionalities & Traveler User Journey

This document describes (1) what Orenda can do today, and (2) the complete
"happy day" journey of a traveler from first sign-up to a finished, personalized
trip plan — with real, sample data that this app can actually produce.

---

## 1. Core Functionalities

Orenda is a Turkey-focused "Travel Operating System". Backend is Spring Boot 4;
all examples below are real REST calls the backend exposes.

| # | Functionality              | API                                  | What it does                                                                 |
|---|----------------------------|--------------------------------------|------------------------------------------------------------------------------|
| 1 | **Traveler onboarding**    | `POST /api/profile`                  | Stores a long-term traveler profile (interests, budget, pace, group, …)      |
| 2 | **POI discovery**          | `GET /api/pois/nearby`               | Spatial search around a lat/lon with optional category filter                 |
| 3 | **POI detail / search**    | `GET /api/pois/search`               | Full-text search (TR + EN names) and single-POI lookup                       |
| 4 | **Categories**             | `GET /api/pois/categories`           | All categories/subcategories with POI counts                                 |
| 5 | **Weather**                | `GET /api/weather`                   | Current conditions + 1–16 day forecast (Open-Meteo, free)                    |
| 6 | **Routing**                | `GET /api/route`                     | Point-to-point routing, profiles: driving / foot / bike (OSRM, free)         |
| 7 | **Trip planning**          | `POST /api/trips/plan`               | Questionnaire → ranked suggestions + timed day-by-day itinerary              |
| 8 | **Preference learning**    | `POST /api/preferences/feedback`     | like/dislike/love/rate → per-category weights + "I noticed…" insight         |
| 9 | **Feedback → constraints** | (same feedback endpoint)             | "too expensive / too far / too crowded / kids / quieter" shape the next plan |
| 10 | **POI quality feedback**   | `POST /api/pois/feedback`            | Report closed/moved/wrong POI → immediate fix + alternative suggestion       |
| 11 | **Personalized enrichment**| `POST /api/preferences/{id}/enrich`  | Enriches the traveler's favorite POI categories first (Wikipedia)            |
| 12 | **AI itinerary narrative** | (inside `POST /api/trips/plan`)      | Natural-language explanation of each day, each stop's "why", adjustments     |
| 13 | **Weather-aware exclusion**| (inside trip planning)               | Open-roof / open-air / outdoor venues are **not** scheduled on rainy days     |
| 14 | **Lunch-time & diet flow** | (inside trip planning)               | At lunch: "hotel or nearby restaurant?", diet pop-up when unknown, diet-filtered picks |

### 1.1 Trip planning pipeline (the core)

```
POST /api/trips/plan
Candidate POIs → Filter → Score → Opening hours → Weather → Travel time
  → Walking constraints → Budget → Optimization → Lunch planning → AI-generated itinerary
```

Every suggestion carries:
- `score` (0–100) and a `factors` breakdown (why this number),
- human-readable `reasons` (e.g. "Open during your trip", "12 min from your base"),
- per-day `startTime` / `endTime` / `travelMinutes` / `visitMinutes`.

Two trip-planning behaviors worth calling out (details in the journey below):

1. **No open-roof venues in the rain.** Venues the traveler would get soaked at —
   parks, viewpoints, ruins, and explicitly open-roof/rooftop/open-air spots (tagged
   `covered=no` / `roof=no`, or named "Rooftop…", "… Roof Terrace") are excluded on
   rainy days and moved to the clear ones. On a trip that is ≥40% rain they are not
   suggested at all.
2. **Interactive lunch.** Every day that doesn't already schedule a lunch stop gets
   a `lunch` block: it asks "head back to the hotel or eat nearby?", returns how far
   the hotel is, and — when the app doesn't know the traveler's diet yet — flags
   `needsDietInfo: true` so the client opens a pop-up. Once the diet is known
   (vegetarian, vegan, halal, gluten-free, lactose-free), nearby restaurants are
   filtered by it. The traveler's diet can be saved in the profile or per-trip.

---

## 2. The Happy-Day User Journey (Step by Step)

> Example traveler: **Selin**, 32, from Ankara. She travels with her husband
> **Emre** and their 6-year-old son **Kaan**. Family trip, 3 nights in Istanbul,
> mid-range budget, balanced pace. She wants history + food, walks a moderate
> amount, prefers local food, and is **vegetarian**. Her `sessionId` is `selin-2026`.

### Step 1 — Create a profile (onboarding, ~1 minute)

Selin opens the app for the first time and answers the short questionnaire
(including her dietary restriction, so Orenda can filter lunch restaurants).
The app calls:

```json
POST /api/profile
{
  "sessionId": "selin-2026",
  "travelerCount": 3,
  "childrenCount": 1,
  "interests": ["HISTORY", "MUSEUMS", "FOOD", "LOCAL_CULTURE"],
  "groupType": "FAMILY",
  "ageRange": "AGE_25_34",
  "mobility": "NONE",
  "pace": "BALANCED",
  "budget": "MID_RANGE",
  "walking": "MODERATE",
  "food": "LOCAL",
  "diet": "VEGETARIAN"
}
```

Response (stored profile):

```json
{
  "sessionId": "selin-2026",
  "travelerCount": 3,
  "childrenCount": 1,
  "interests": ["HISTORY", "MUSEUMS", "FOOD", "LOCAL_CULTURE"],
  "groupType": "FAMILY",
  "ageRange": "AGE_25_34",
  "mobility": "NONE",
  "pace": "BALANCED",
  "budget": "MID_RANGE",
  "walking": "MODERATE",
  "food": "LOCAL",
  "diet": "VEGETARIAN",
  "updatedAt": "2026-08-10T09:14:22+03:00"
}
```

### Step 2 — Browse what's around / check categories

Before planning, Selin looks at the map around her hotel in Sultanahmet.
The app calls `GET /api/pois/nearby`:

```
GET /api/pois/nearby?lat=41.0082&lon=28.9784&radiusKm=5&page=0&size=10
```

```json
[
  {
    "id": "7f3a9c21-...",
    "osmId": 42137019,
    "nameTr": "Ayasofya-i Kebir Camii",
    "nameEn": "Hagia Sophia Grand Mosque",
    "category": "historic",
    "subcategory": "place_of_worship",
    "lat": 41.0086,
    "lon": 28.9802,
    "completenessScore": 92,
    "distanceKm": 0.2,
    "attributes": { "wikidata": "Q12500", "opening_hours": "Mo-Su 09:00-17:00" }
  },
  {
    "id": "c2b8d410-...",
    "osmId": 9338645,
    "nameTr": "Topkapı Sarayı",
    "nameEn": "Topkapi Palace",
    "category": "historic",
    "subcategory": "palace",
    "lat": 41.0115,
    "lon": 28.9834,
    "completenessScore": 88,
    "distanceKm": 0.5,
    "attributes": { "opening_hours": "We-Mo 09:00-18:00", "phone": "+90 212 512 04 80" }
  },
  ...
]
```

She can also ask the app which categories exist and how many POIs each has:

```
GET /api/pois/categories
```

```json
[
  { "category": "historic", "subcategory": "palace", "count": 214 },
  { "category": "historic", "subcategory": "mosque", "count": 1023 },
  { "category": "food", "subcategory": "restaurant", "count": 8412 },
  { "category": "shopping", "subcategory": "marketplace", "count": 96 },
  ...
]
```

### Step 3 — Check the weather for the trip dates

Selin taps "weather" for her location:

```
GET /api/weather?lat=41.0082&lon=28.9784&days=4
```

```json
{
  "location": { "latitude": 41.0082, "longitude": 28.9784, "timezone": "Europe/Istanbul" },
  "current": { "temperature": 28.4, "feelsLike": 29.1, "humidity": 62,
               "weatherCode": 2, "description": "Partly cloudy" },
  "daily": [
    { "date": "2026-08-15", "maxTemp": 31.0, "minTemp": 21.5, "precipitation": 0.0,
      "weatherCode": 0, "description": "Clear sky" },
    { "date": "2026-08-16", "maxTemp": 27.2, "minTemp": 19.8, "precipitation": 6.4,
      "weatherCode": 61, "description": "Light rain" },
    { "date": "2026-08-17", "maxTemp": 26.1, "minTemp": 19.0, "precipitation": 12.1,
      "weatherCode": 63, "description": "Rain" },
    { "date": "2026-08-18", "maxTemp": 29.3, "minTemp": 20.2, "precipitation": 0.0,
      "weatherCode": 1, "description": "Mainly clear" }
  ]
}
```

Selin notices: rainy days on 16–17 → the planner will push indoor venues those days.

### Step 4 — Plan the trip (the main event)

Selin fills the four-part questionnaire. Because she gave `sessionId`,
the planner blends in her stored profile and any learned preferences.

```json
POST /api/trips/plan
{
  "basics": {
    "destination": "Istanbul",
    "startDate": "2026-08-15",
    "endDate": "2026-08-18",
    "travelerCount": 3,
    "childrenCount": 1,
    "accommodationLocation": "41.0082,28.9784",
    "transportMode": "FOOT"
  },
  "profile": {
    "ageRange": "AGE_25_34",
    "groupType": "FAMILY",
    "childAgeRanges": ["UNDER_18"],
    "mobilityLimitation": "NONE",
    "diet": "VEGETARIAN"
  },
  "interests": {
    "selectedInterests": ["HISTORY", "MUSEUMS", "FOOD"],
    "additionalNotes": "prefer mornings, one Bosphorus cruise if the weather allows"
  },
  "style": {
    "pace": "BALANCED",
    "walking": "MODERATE",
    "budget": "MID_RANGE",
    "food": "LOCAL",
    "planningStyle": "DETAILED_SCHEDULE"
  },
  "sessionId": "selin-2026"
}
```

Response — the planner explains *why*:

```json
{
  "tripDays": 4,
  "summary": "Found 20 suggestions for your 4-day family trip to Istanbul. Top match: Topkapı Sarayı (historic).",
  "weatherSummary": "AUG 15: Clear sky 31°C · AUG 16: Light rain 27°C · AUG 17: Rain 26°C · AUG 18: Mainly clear 29°C",
  "preferenceInsight": "I noticed you tend to prefer cultural experiences and local food. I've adjusted your recommendations accordingly.",
  "narrative": "I've planned a 4-day family trip to Istanbul at a balanced pace, mostly on foot around Sultanahmet. Rain on Aug 16–17, so those days lean on indoor stops; clear skies on Aug 15 and 18 are saved for the outdoors and the Bosphorus cruise. I kept buffer time around meals for a 6-year-old.",
  "suggestions": [
    {
      "poi": { "id": "c2b8d410-...", "nameTr": "Topkapı Sarayı", "category": "historic", "lat": 41.0115, "lon": 28.9834 },
      "score": 87.3,
      "factors": { "interest_match": 25.0, "opening_hours": 10.0, "weather": 5.0,
                   "preference": 9.2, "travel_time": 8.4, "family_suitability": 15.0 },
      "reasons": [
        "Matches your interest in history",
        "Open during your trip (closed Tuesdays — not on your schedule)",
        "Great for families with children",
        "12 min from your base"
      ],
      "travelMinutes": 12,
      "visitMinutes": 120
    },
    { "poi": { "id": "7f3a9c21-...", "nameTr": "Ayasofya-i Kebir Camii", "score": 84.1 }, "...": "..." },
    { "poi": { "id": "...", "nameTr": "Yerebatan Sarnıcı", "category": "historic", "score": 81.7 },
      "reasons": ["Mostly indoor — well suited to the rainy forecast"] },
    { "poi": { "id": "...", "nameTr": "Bosphorus Cruise", "category": "nature", "score": 79.2 },
      "reasons": ["Clear conditions during your trip — great for outdoor spots"] }
  ],
  "notes": ["Rain expected on 2 of your 4 days — indoor venues are boosted.",
            "Open-roof / rooftop venues excluded on the rainy days (Aug 16–17)."]
  "dayPlan": [
    {
      "day": 1,
      "date": "2026-08-15",
      "weather": "Clear sky, 31°C",
      "items": [
        { "poi": { "nameTr": "Topkapı Sarayı" }, "startTime": "09:30", "endTime": "11:30",
          "travelMinutes": 12, "visitMinutes": 120, "openAtScheduledTime": true,
          "reasons": ["Matches your interest in history", "12 min from your base"] },
        { "poi": { "nameTr": "Ayasofya-i Kebir Camii" }, "startTime": "11:50", "endTime": "13:00",
          "travelMinutes": 5, "visitMinutes": 70, "openAtScheduledTime": true },
        { "poi": { "nameTr": "Yerebatan Sarnıcı" }, "startTime": "14:30", "endTime": "15:15",
          "travelMinutes": 4, "visitMinutes": 45 }
      ],
      "notes": ["31°C at midday — plan a shady break."],
      "lunch": {
        "prompt": "Lunch time — head back to the hotel, or should I pick a restaurant nearby?",
        "needsDietInfo": false,
        "nearbyRestaurants": [
          { "poi": { "id": "...", "nameTr": "Karadeniz Aile Pide", "category": "food_drink",
                     "lat": 41.0109, "lon": 28.9731 },
            "score": 89.2, "factors": { "diet_match": 10.0, "proximity": 12.4 },
            "reasons": ["Fits your vegetarian diet", "About 0.3 km / 4 min from your stop"],
            "travelMinutes": 4, "visitMinutes": 60 },
          { "poi": { "id": "...", "nameTr": "Sultanahmet Köftecisi", "category": "food_drink" },
            "score": 81.0, "reasons": ["Fits your vegetarian diet", "About 0.5 km / 6 min from your stop"] }
        ],
        "returnToHotel": { "travelMinutes": 12, "distanceKm": 0.9 },
        "note": "2 vegetarian-friendly places near your lunch stop."
      },
      "narrative": "Day 1 · Saturday, Aug 15 — Clear sky, 31°C. The day starts at Topkapı Palace at 09:30 before the heat peaks, then Hagia Sophia next door. Around lunch the app asks: back to the hotel (12 min) or a vegetarian-friendly restaurant near the mosque — it offers two nearby picks. After lunch, a short cool stop at the Basilica Cistern."
    },
    {
      "day": 2,
      "date": "2026-08-16",
      "weather": "Light rain, 27°C",
      "items": [
        { "poi": { "nameTr": "İstanbul Arkeoloji Müzeleri" }, "startTime": "10:00", "endTime": "12:30",
          "openAtScheduledTime": true, "reasons": ["Mostly indoor — well suited to the rainy forecast"] },
        { "poi": { "nameTr": "Kapalıçarşı (Grand Bazaar)" }, "startTime": "14:00", "endTime": "16:00",
          "reasons": ["Matches your interest in local culture"] }
      ],
      "notes": ["Rain all day — itinerary is indoor-first.",
                "Open-roof venues (rooftop terraces, parks, viewpoints) are excluded today.",
                "Rain expected on 2 of your 4 days — indoor venues are boosted."],
      "lunch": {
        "prompt": "Lunch time — head back to the hotel, or should I pick a restaurant nearby?",
        "needsDietInfo": false,
        "nearbyRestaurants": [
          { "poi": { "id": "...", "nameTr": "Zencefil Restaurant", "category": "food_drink" },
            "score": 91.0, "reasons": ["Fits your vegetarian diet", "Covered — stays dry in the rain"] },
          { "poi": { "id": "...", "nameTr": "Cafe Privato", "category": "food_drink" },
            "score": 86.4, "reasons": ["Fits your vegetarian diet", "About 0.4 km / 5 min from your stop"] }
        ],
        "returnToHotel": { "travelMinutes": 18, "distanceKm": 1.4 },
        "note": "2 vegetarian-friendly places near your lunch stop — all under cover for the rain."
      },
      "narrative": "Day 2 · Sunday, Aug 16 — Light rain, 27°C, so today stays indoors. The Archaeology Museums open at 10:00; around lunch the app offers covered, vegetarian-friendly restaurants near the museum rather than outdoor terraces. After a covered stroll through the Grand Bazaar for souvenirs and lokum, you're done by 16:00."
    },
    {
      "day": 3,
      "date": "2026-08-17",
      "weather": "Rain, 26°C",
      "items": [
        { "poi": { "nameTr": "Rahmi M. Koç Müzesi" }, "startTime": "10:30", "endTime": "13:30",
          "reasons": ["Great for families with children", "Mostly indoor — well suited to the rainy forecast"] }
      ],
      "notes": ["Heaviest rain of the trip — a single, long indoor stop keeps the family dry and the day relaxed."],
      "narrative": "Day 3 · Monday, Aug 17 — Rain, 26°C. Kaan loves machines, so the day is one relaxed visit to the Rahmi Koç Museum, with plenty of lunch and rest breaks."
    },
    {
      "day": 4,
      "date": "2026-08-18",
      "weather": "Mainly clear, 29°C",
      "items": [
        { "poi": { "nameTr": "Bosphorus Cruise" }, "startTime": "10:00", "endTime": "12:30",
          "reasons": ["Clear conditions during your trip — great for outdoor spots", "Matches your interest in nature"] },
        { "poi": { "nameTr": "Galata Kulesi" }, "startTime": "15:00", "endTime": "16:00",
          "reasons": ["Matches your interest in photography"] }
      ],
      "notes": ["Departure day — final activities before your 18:00 flight."],
      "narrative": "Day 4 · Tuesday, Aug 18 — Mainly clear, 29°C. The weather cleared just in time for the Bosphorus cruise you asked about. After lunch in Karaköy, a quick photo stop at Galata Tower, then to the airport."
    }
  ],
  "notes": ["Rain expected on 2 of your 4 days — indoor venues are boosted."]
}
```

> **What the planner just did for Selin:** it refused to put any open-roof venue on
> Aug 16–17 (no rooftop terrace, no Gülhane Park stroll while it rains — those are
> moved to the clear days or dropped), and it attached a `lunch` block to every day.

### Step 4b — Lunch time: hotel or nearby restaurant? (diet pop-up)

Around 12:00 each day the app shows Selin the day's `lunch` block. Because her
profile already includes `"diet": "VEGETARIAN"`, `needsDietInfo` is `false` and the
app goes straight to vegetarian-filtered picks near wherever she is.

**What happens when the diet is NOT known yet?** Say a new traveler, Burak, plans
his trip without saving a profile. His plan returns a `lunch` block like this:

```json
{
  "prompt": "Lunch time — head back to the hotel, or should I pick a restaurant nearby?",
  "needsDietInfo": true,
  "nearbyRestaurants": [],
  "returnToHotel": { "travelMinutes": 10, "distanceKm": 0.7 },
  "note": "We don't know your dietary needs yet — tell us and I'll pick better restaurants."
}
```

`needsDietInfo: true` is the signal that makes the client open a **pop-up**:
"Any dietary needs? Vegetarian · Vegan · Halal · Gluten-free · Lactose-free · None".

Burak taps **Vegan**, and the client sends it:

```json
POST /api/profile
{
  "sessionId": "burak-2026",
  "diet": "VEGAN"
}
```

The next time his plan (or his current day's lunch refresh) is generated, the app
queries restaurants near his lunch-time location and filters them by his vegan diet:

```json
{
  "prompt": "Lunch time — head back to the hotel, or should I pick a restaurant nearby?",
  "needsDietInfo": false,
  "nearbyRestaurants": [
    { "poi": { "nameTr": "Vegan Istanbul", "category": "food_drink" },
      "reasons": ["Fits your vegan diet", "About 0.2 km / 3 min from your stop"] },
    { "poi": { "nameTr": "Community Kitchen", "category": "food_drink" },
      "reasons": ["Fits your vegan diet", "About 0.6 km / 8 min from your stop"] }
  ],
  "returnToHotel": { "travelMinutes": 10, "distanceKm": 0.7 },
  "note": "2 vegan-friendly places near your lunch stop."
}
```

Rules of thumb for the lunch block:
- The traveler is asked once per day where a lunch stop isn't already scheduled.
- The app always offers **both** options: hotel (`returnToHotel` minutes/km) and
  nearby restaurants (`nearbyRestaurants`).
- Restaurant picks are limited to real meal venues (restaurant / cafe / fast food /
  food court) — bars and ice-cream shops are not lunch suggestions.
- When the diet is set, matches are ranked first and each pick says *why* it fits
  ("Fits your halal diet"); when nothing matches, the app shows the closest places
  and says so.

### Step 5 — Check a route between two places

On the map, Selin taps "how do we get from Galata to the airport?"

```
GET /api/route?fromLat=41.0218&fromLon=28.9744&toLat=41.2753&toLon=28.7519&profile=driving
```

```json
{
  "distanceKm": 42.8,
  "durationMinutes": 51.0,
  "profile": "driving",
  "summary": "D100, O-3",
  "geometry": { "type": "LineString", "coordinates": [[28.9744, 41.0218], [28.9751, 41.0220], "..."] }
}
```

She can also use `profile=foot` for the walk from Topkapı to Hagia Sophia (5 min).

### Step 6 — Give feedback (this is what makes Orenda personal)

At the Bosphorus cruise, Selin loves it. That night she rates the Archaeology
Museums a 5 and says "find something similar". The app records the reactions:

```json
POST /api/preferences/feedback
{
  "poiId": "c2b8d410-...",
  "sessionId": "selin-2026",
  "reaction": "LOVE",
  "rating": 5,
  "reason": "FIND_SIMILAR"
}
```

Response — weights are updated immediately:

```json
{
  "accepted": true,
  "message": "Noted — adding more places like this to your recommendations.",
  "updatedWeights": { "CULTURE": 0.87, "FOOD": 0.64, "SHOPPING": 0.31, "NIGHTLIFE": 0.05 },
  "insight": "I noticed you tend to prefer cultural experiences. You seem to skip nightlife and shopping. I've adjusted your recommendations accordingly.",
  "similarPois": [ { "id": "...", "nameTr": "Dolmabahçe Sarayı", "category": "historic" } ]
}
```

She also reports that a restaurant the app suggested is permanently closed:

```json
POST /api/pois/feedback
{
  "poiId": "9f2d3e11-...",
  "type": "CLOSED",
  "details": "Permanently closed since May",
  "sessionId": "selin-2026"
}
```

```json
{
  "accepted": true,
  "message": "Feedback received — POI marked as closed and excluded from future results.",
  "alternativePoi": { "id": "...", "nameTr": "Karadeniz Aile Pide", "category": "food", "lat": 41.0109, "lon": 28.9731 }
}
```

### Step 7 — Enrich her favorite category

Selin is into culture, so she taps "improve cultural POIs for me":

```
POST /api/preferences/{selin-2026}/enrich
```

```json
{ "accepted": true, "status": "STARTED" }
```

Orenda's enrichment job now prioritizes Wikipedia summaries/images for the
cultural POIs Selin actually cares about — not random POIs.

### Step 8 — The next trip is already smarter

Two weeks later Selin plans a Cappadocia trip with `sessionId: selin-2026`.
The planner automatically:

- uses her stored baseline interests if she leaves the interest list empty,
- boosts cultural venues (learned CULTURE weight 0.87),
- keeps search radius small (her "too far" feedback from Istanbul),
- excludes nightclubs/bars (she's traveling with Kaan),
- keeps her **vegetarian diet** in mind for every lunch suggestion,
- explains all of it in the `narrative`.

```json
GET /api/preferences/selin-2026
```

```json
{
  "CULTURE": 0.87,
  "FOOD": 0.64,
  "SHOPPING": 0.31,
  "NIGHTLIFE": 0.05
}
```

---

## 3. Summary of the journey

| Step | What Selin does                          | Endpoint(s)                                    | What Orenda learns / returns                  |
|------|------------------------------------------|------------------------------------------------|-----------------------------------------------|
| 1    | Answers onboarding (≈1 min)              | `POST /api/profile`                            | Long-term profile (baseline interests, **diet**) |
| 2    | Browsers nearby + categories              | `GET /api/pois/nearby`, `/categories`          | Ranked POIs around her hotel                  |
| 3    | Checks the forecast                       | `GET /api/weather`                             | Trip dates weather (drives indoor/outdoor, **excludes open-roof on rainy days**) |
| 4    | Fills the 4-part questionnaire            | `POST /api/trips/plan`                         | Ranked suggestions + timed day plan + story   |
| 4b   | Answers the lunch question (+ diet pop-up when unknown) | (inside day plan `lunch` block)   | "Hotel or nearby?" + **diet-matched restaurant picks** |
| 5    | Checks a route                            | `GET /api/route`                               | Distance, duration, GeoJSON map line          |
| 6    | Rates / reports                           | `POST /api/preferences/feedback`, `/api/pois/feedback` | Weights + constraints + data fixes    |
| 7    | Improves her favorite category            | `POST /api/preferences/{id}/enrich`            | Loved categories enriched first               |
| 8    | Plans the *next* trip                     | `POST /api/trips/plan` (+ `sessionId`)         | Everything she taught the app is applied      |

The key idea: **the app gets better every time the traveler interacts** — a
profile, a reaction, a rating, a diet, a "too expensive" complaint, or a report
that a place closed all feed back into the next plan, and Orenda always explains
*why* it made the choices it made — including why an open-roof venue was skipped
on a rainy day, and which restaurants near the traveler fit their diet.
