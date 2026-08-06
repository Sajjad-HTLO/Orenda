-- Allow muze.gov.tr-sourced POIs (osm_type = 'G') alongside OSM N/W/R and Wikidata Q
ALTER TABLE poi DROP CONSTRAINT IF EXISTS poi_osm_type_check;
ALTER TABLE poi ADD CONSTRAINT poi_osm_type_check CHECK (osm_type IN ('N', 'W', 'R', 'Q', 'G'));