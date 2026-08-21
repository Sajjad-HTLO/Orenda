-- Store POI images as files on local disk instead of binary blobs in the DB.
-- The poi_image table now keeps only a reference (file_path) to the image file
-- on this machine, plus metadata. This avoids large binary blobs in the DB
-- (important for Neon's 512MB project limit) while still allowing the images to
-- be located and served later.
ALTER TABLE poi_image DROP COLUMN IF EXISTS image_data;
ALTER TABLE poi_image ADD COLUMN file_path TEXT;

-- Unique reference to the image file on this machine (e.g. tripadvisor/{osmId}/{uuid}.jpg)
CREATE UNIQUE INDEX IF NOT EXISTS poi_image_file_path_idx ON poi_image (file_path)
    WHERE file_path IS NOT NULL;
