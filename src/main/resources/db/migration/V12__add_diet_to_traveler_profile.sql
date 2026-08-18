-- Traveler's dietary restriction (NONE, VEGETARIAN, VEGAN, HALAL, GLUTEN_FREE, LACTOSE_FREE).
-- Optional; the app asks for it via the lunch-time pop-up when it is unknown.
ALTER TABLE traveler_profile ADD COLUMN IF NOT EXISTS diet VARCHAR(20);
