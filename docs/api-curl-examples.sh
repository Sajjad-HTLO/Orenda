#!/usr/bin/env bash
# =============================================================================
# Orenda API — curl examples (base: https://orenda-d9qj.onrender.com)
#
# Usage: copy/paste individual commands, or run this file as a script.
# All commands are independent. See the section headers below.
#
# The following shell variables are used throughout. Replace them once at the
# top and the rest of the file works:
#   BASE       - API base URL
#   TOKEN      - JWT returned by POST /api/auth/login (or the Google callback)
#   SESSION_ID - anonymous client id used by preferences/profile/trips
#   POI_ID     - a real POI uuid from GET /api/pois/search
#   TRIP_ID    - a real saved-trip uuid from GET /api/trips
# =============================================================================

export BASE="https://orenda-d9qj.onrender.com"
export TOKEN=""                       # e.g. "eyJhbGciOiJIUzI1NiJ9...."
export SESSION_ID="demo-traveler-42"
export POI_ID="REPLACE_WITH_POI_UUID" # from search/nearby below
export TRIP_ID="REPLACE_WITH_TRIP_UUID"
export STOP_ID="REPLACE_WITH_STOP_UUID"

# =============================================================================
# 1. AUTH — signup, email verification, login, Google OAuth, /me
# =============================================================================

# Create an account (email + password). A verification email is sent.
curl -s -X POST "$BASE/api/auth/signup" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "traveler@example.com",
    "password": "secret123",
    "fullName": "Ayşe Yılmaz"
  }'

# Confirm the email address (token from the verification email).
curl -s "$BASE/api/auth/verify-email?token=REPLACE_WITH_VERIFICATION_TOKEN"

# Login -> returns { token, tokenType, user }. Copy token into TOKEN above.
curl -s -X POST "$BASE/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "traveler@example.com",
    "password": "secret123"
  }'

# Re-send the verification email.
curl -s -X POST "$BASE/api/auth/resend-verification" \
  -H "Content-Type: application/json" \
  -d '{"email": "traveler@example.com"}'

# Open Google's consent page in a browser (redirect, not JSON).
curl -s -L "$BASE/api/auth/google"

# Google redirects the browser here after sign-in; it forwards to the frontend
# with ?token=<jwt>. Not meant to be called manually.
curl -s -L "$BASE/api/auth/google/callback?code=REPLACE_WITH_CODE&state=REPLACE_WITH_STATE"

# Current authenticated user (requires TOKEN).
curl -s "$BASE/api/auth/me" \
  -H "Authorization: Bearer $TOKEN"

# =============================================================================
# 2. USER PROFILE — personal details (requires TOKEN)
# =============================================================================

# Read the authenticated user's profile.
curl -s "$BASE/api/users/profile" \
  -H "Authorization: Bearer $TOKEN"

# Update display name, avatar, home city and dietary restrictions.
curl -s -X PUT "$BASE/api/users/profile" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "fullName": "Ayşe Yılmaz",
    "avatarUrl": "https://example.com/avatars/ayse.png",
    "homeCity": "İstanbul",
    "dietaryRestrictions": ["vegetarian", "no-pork"]
  }'

# =============================================================================
# 3. POI SEARCH & DISCOVERY — public
# =============================================================================

# POIs within a radius of a point (nearest first).
curl -s "$BASE/api/pois/nearby?lat=41.0082&lon=28.9784&radiusKm=5&page=0&size=20"

# Nearby, filtered by category.
curl -s "$BASE/api/pois/nearby?lat=41.0082&lon=28.9784&radiusKm=5&category=historic&page=0&size=20"

# Full-text search over Turkish/English names.
curl -s "$BASE/api/pois/search?q=topkapi&page=0&size=20"

# All categories and subcategories with counts.
curl -s "$BASE/api/pois/categories"

# Single POI by uuid.
curl -s "$BASE/api/pois/$POI_ID"

# =============================================================================
# 4. POI FEEDBACK — report closed/inaccurate/moved/duplicate/other
# =============================================================================

# Mark a POI as closed (returns the nearest alternative POI).
curl -s -X POST "$BASE/api/pois/feedback" \
  -H "Content-Type: application/json" \
  -d "{
    \"poiId\": \"$POI_ID\",
    \"type\": \"CLOSED\",
    \"details\": \"Permanently closed since 2025\",
    \"sessionId\": \"$SESSION_ID\"
  }"

# Report a moved POI with corrected coordinates.
curl -s -X POST "$BASE/api/pois/feedback" \
  -H "Content-Type: application/json" \
  -d "{
    \"poiId\": \"$POI_ID\",
    \"type\": \"MOVED\",
    \"details\": \"Location is off by ~200m\",
    \"newLat\": 41.0088,
    \"newLon\": 28.9792
  }"

# =============================================================================
# 5. POI REVIEWS — public list, authenticated submit (POST requires TOKEN)
# =============================================================================

# List traveler reviews for a POI (public).
curl -s "$BASE/api/pois/$POI_ID/reviews"

# Submit a review/rating (requires TOKEN). rating is 1-5.
curl -s -X POST "$BASE/api/pois/$POI_ID/reviews" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "rating": 5,
    "title": "Breathtaking views",
    "comment": "Arrive early to skip the queues. The harem section is a must."
  }'

# =============================================================================
# 6. WEATHER — Open-Meteo, public
# =============================================================================

# Current conditions + daily forecast (days 1-16).
curl -s "$BASE/api/weather?lat=41.0082&lon=28.9784&days=7"

# =============================================================================
# 7. ROUTING — OSRM, public (profile: driving | foot | bike)
# =============================================================================

# Point-to-point route (Istanbul -> Antalya, driving).
curl -s "$BASE/api/route?fromLat=41.0082&fromLon=28.9784&toLat=36.8841&toLon=30.7056&profile=driving"

# Walking route across old Istanbul.
curl -s "$BASE/api/route?fromLat=41.0082&fromLon=28.9784&toLat=41.0115&toLon=28.9835&profile=foot"

# =============================================================================
# 8. TRIP PLANNER — public
# =============================================================================

# Generate a ranked, day-by-day plan from the questionnaire.
curl -s -X POST "$BASE/api/trips/plan" \
  -H "Content-Type: application/json" \
  -d '{
    "basics": {
      "destination": "İstanbul",
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
      "mobilityLimitation": "NONE",
      "diet": "NONE"
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
    "sessionId": "'"$SESSION_ID"'"
  }'

# =============================================================================
# 9. SAVED TRIPS — all require TOKEN
# =============================================================================

# List the user's saved trips.
curl -s "$BASE/api/trips" \
  -H "Authorization: Bearer $TOKEN"

# Save a freshly generated plan (paste the TripPlanResponse JSON as "plan").
curl -s -X POST "$BASE/api/trips" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Istanbul family getaway",
    "destination": "İstanbul",
    "plan": {
      "tripDays": 4,
      "summary": "Found 12 suggestions for your 4-day family trip to İstanbul. Top match: Topkapı Palace (historic).",
      "weatherSummary": "AUG 15: Slight rain, 20°C · AUG 16: Clear sky, 26°C",
      "suggestions": [],
      "dayPlan": [
        {
          "day": 1,
          "date": "2026-08-15",
          "weather": "Slight rain, 20°C",
          "items": [
            {
              "poi": { "id": "'"$POI_ID"'", "nameTr": "Topkapı Palace", "category": "historic" },
              "score": 81.5,
              "travelMinutes": 12,
              "visitMinutes": 120,
              "startTime": "09:40",
              "endTime": "11:40",
              "openAtScheduledTime": true,
              "reasons": ["Matches your interest in history"]
            }
          ],
          "notes": ["Lunch break recommended around 13:00."]
        }
      ],
      "notes": [],
      "narrative": "I have planned a 4-day family trip to İstanbul at a balanced pace."
    }
  }'

# Full detail of one saved trip (days + stops).
curl -s "$BASE/api/trips/$TRIP_ID" \
  -H "Authorization: Bearer $TOKEN"

# Update / reorder stops / adjust notes (PUT). Reorder = reorder the stops array.
curl -s -X PUT "$BASE/api/trips/$TRIP_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Istanbul family getaway (updated)",
    "notes": ["Moved Hagia Sophia to day 2 after the feedback."],
    "days": [
      {
        "day": 1,
        "date": "2026-08-15",
        "weather": "Slight rain, 20°C",
        "stops": [
          {
            "poiId": "'"$POI_ID"'",
            "nameTr": "Topkapı Palace",
            "category": "historic",
            "subcategory": "palace",
            "lat": 41.0115,
            "lon": 28.9835,
            "score": 81.5,
            "travelMinutes": 12,
            "visitMinutes": 120,
            "startTime": "09:40",
            "endTime": "11:40",
            "openAtScheduledTime": true,
            "reasons": ["Matches your interest in history"]
          }
        ]
      }
    ]
  }'

# PATCH is an alias of PUT.
curl -s -X PATCH "$BASE/api/trips/$TRIP_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name": "Istanbul family getaway (patched)"}'

# Delete (hard) a saved trip.
curl -s -X DELETE "$BASE/api/trips/$TRIP_ID" \
  -H "Authorization: Bearer $TOKEN"

# Archive (soft delete) a saved trip.
curl -s -X DELETE "$BASE/api/trips/$TRIP_ID?archive=true" \
  -H "Authorization: Bearer $TOKEN"

# Export the itinerary as iCal (.ics) or PDF.
curl -s -o trip.ics "$BASE/api/trips/$TRIP_ID/export?format=ics" \
  -H "Authorization: Bearer $TOKEN"
curl -s -o trip.pdf "$BASE/api/trips/$TRIP_ID/export?format=pdf" \
  -H "Authorization: Bearer $TOKEN"

# Recalculate remaining stops for a real-time event (RAIN | DELAY | ROAD_CLOSURE).
curl -s -X POST "$BASE/api/trips/recalculate" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"tripId\": \"$TRIP_ID\",
    \"event\": \"RAIN\",
    \"eventDate\": \"2026-08-16\",
    \"note\": \"Forecast changed - heavy rain in the afternoon.\"
  }"

# Delay: shift remaining times 45 minutes later.
curl -s -X POST "$BASE/api/trips/recalculate" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"tripId\": \"$TRIP_ID\",
    \"event\": \"DELAY\",
    \"eventDate\": \"2026-08-16\",
    \"delayMinutes\": 45
  }"

# Road closure: drop an unreachable stop.
curl -s -X POST "$BASE/api/trips/recalculate" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"tripId\": \"$TRIP_ID\",
    \"event\": \"ROAD_CLOSURE\",
    \"eventDate\": \"2026-08-16\",
    \"affectedStopId\": \"$STOP_ID\",
    \"note\": \"Street closed for repairs.\"
  }"

# =============================================================================
# 10. PREFERENCES — anonymous session learning (public)
# =============================================================================

# Current learned per-category weights for a session.
curl -s "$BASE/api/preferences/$SESSION_ID"

# Record a reaction to a suggested POI.
curl -s -X POST "$BASE/api/preferences/feedback" \
  -H "Content-Type: application/json" \
  -d "{
    \"poiId\": \"$POI_ID\",
    \"sessionId\": \"$SESSION_ID\",
    \"reaction\": \"LOVE\",
    \"reason\": \"FIND_SIMILAR\",
    \"details\": \"Loved the history - want more palaces.\"
  }"

# Rate a POI (1-5).
curl -s -X POST "$BASE/api/preferences/feedback" \
  -H "Content-Type: application/json" \
  -d "{
    \"poiId\": \"$POI_ID\",
    \"sessionId\": \"$SESSION_ID\",
    \"reaction\": \"RATED\",
    \"rating\": 5
  }"

# Launch Wikipedia enrichment prioritized by this session's learned weights.
curl -s -X POST "$BASE/api/preferences/$SESSION_ID/enrich"

# =============================================================================
# 11. ONBOARDING PROFILE — public
# =============================================================================

# Upsert the traveler's long-term profile.
curl -s -X POST "$BASE/api/profile" \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "'"$SESSION_ID"'",
    "travelerCount": 2,
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
  }'

# Retrieve the profile for a session.
curl -s "$BASE/api/profile/$SESSION_ID"

# =============================================================================
# 12. DATA IMPORTS — available only when the job is enabled (else 404/503)
# =============================================================================

# Manually trigger the Wikidata import job.
curl -s -X POST "$BASE/api/wikidata/import?reset=false"

# Manually trigger the Overpass import job.
curl -s -X POST "$BASE/api/overpass/import"

# =============================================================================
# 13. HELPER — fetch a real POI uuid so the examples above can be filled in
# =============================================================================

# Grab the first nearby POI id and print it:
#   curl -s "$BASE/api/pois/nearby?lat=41.0082&lon=28.9784&radiusKm=3&page=0&size=1" \
#     | python3 -c "import sys,json; print(json.load(sys.stdin)[0]['id'])"