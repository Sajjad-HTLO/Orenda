-- Allow Wikidata-sourced POIs (osm_type = 'Q') alongside OSM N/W/R
ALTER TABLE poi DROP CONSTRAINT IF EXISTS poi_osm_type_check;
ALTER TABLE poi ADD CONSTRAINT poi_osm_type_check CHECK (osm_type IN ('N', 'W', 'R', 'Q'));