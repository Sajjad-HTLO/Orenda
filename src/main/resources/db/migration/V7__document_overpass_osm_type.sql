-- Allow Overpass-sourced POIs (osm_type = 'N', 'W', 'R' — same as OSM, but sourced from Overpass API)
-- No schema change needed since Overpass uses the same osm_type values as OSM PBF import.
-- The data_sources array distinguishes them: ARRAY['overpass'] vs ARRAY['osm'].
-- This migration exists for documentation and future extensibility.

-- Add a comment to the poi table documenting all valid osm_type values
COMMENT ON COLUMN poi.osm_type IS 'OSM element type: N=node, W=way, R=relation (from OSM PBF or Overpass), Q=Wikidata, G=muze.gov.tr, P=Wikipedia geosearch';