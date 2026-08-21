package com.aitp.orenda.tripadvisor.image;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Slf4j
@Repository
public class ImageRepository {

    private final JdbcTemplate jdbcTemplate;

    public ImageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Records that an image was downloaded and stored at {@code filePath} on
     * this machine. Deduplicates by (osm_id, osm_type, source_url). Returns the
     * number of rows inserted (1) or 0 when the same source URL was already
     * recorded.
     */
    public int upsertImage(long osmId, String osmType, String source, String sourceUrl,
                           String mimeType, Integer width, Integer height, String filePath, long fileSize) {
        if (filePath == null || filePath.isBlank()) {
            return 0;
        }
        int rows = jdbcTemplate.update("""
                INSERT INTO poi_image (
                    osm_id, osm_type, source, source_url, mime_type, width, height,
                    file_path, file_size, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
                ON CONFLICT (osm_id, osm_type, source, source_url) DO NOTHING
                """,
                osmId, osmType, source, sourceUrl, mimeType, width, height, filePath, fileSize);
        return rows;
    }

    public int countImages() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM poi_image", Long.class);
        return count == null ? 0 : count.intValue();
    }

    public int countImagesForPoi(long osmId, String osmType) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM poi_image WHERE osm_id = ? AND osm_type = ?",
                Long.class, osmId, osmType);
        return count == null ? 0 : count.intValue();
    }
}