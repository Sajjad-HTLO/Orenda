

to see progress

docker exec aitp-pg psql -U postgres -d aitp -c "
SELECT
  count(*) AS total_tripadvisor,
  count(*) FILTER (WHERE attributes ? 'rating')             AS with_rating,
  count(*) FILTER (WHERE attributes ? 'image_urls')         AS with_images,
  count(*) FILTER (WHERE attributes ? 'source_listing_url') AS with_source
FROM poi WHERE osm_type='T';"

