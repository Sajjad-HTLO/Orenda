package com.sajad.AITP.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PoiFeedbackIntegrationTest {

    // Synthetic osm_ids (far above real OSM range) so we never touch production data.
    private static final long TEST_OSM_ID = 9_999_999_999_998L;
    private static final long TEST_OSM_ID_ALT = 9_999_999_999_997L;

    private static final double TEST_LAT = 41.0100;
    private static final double TEST_LON = 28.9700;
    private static final double ALT_LAT = 41.0101;   // ~11m away
    private static final double ALT_LON = 28.9701;
    private static final String TEST_CAT = "test_feedback";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper jackson = new ObjectMapper();

    private RestClient client;

    private String reportedPoiId;
    private String alternativePoiId;

    @BeforeAll
    void requireImportedPoiData() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM poi", Integer.class);
        assumeTrue(count != null && count > 0,
                "Requires PostGIS with imported POI data (start aitp-pg and run the import job)");
    }

    @BeforeEach
    void setUp() {
        client = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();

        // Idempotent cleanup so re-runs and parallel test suites don't break.
        jdbc.update("DELETE FROM poi WHERE osm_id IN (?, ?)", TEST_OSM_ID, TEST_OSM_ID_ALT);

        // Insert two synthetic POIs: the one the user reports, and one that
        // serves as a guaranteed alternative in the same tiny area.
        reportedPoiId = insertSynthetic(TEST_OSM_ID, "Test POI Reported", TEST_LAT, TEST_LON);
        alternativePoiId = insertSynthetic(TEST_OSM_ID_ALT, "Test POI Alternative", ALT_LAT, ALT_LON);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM poi WHERE osm_id IN (?, ?)", TEST_OSM_ID, TEST_OSM_ID_ALT);
    }

    private String insertSynthetic(long osmId, String name, double lat, double lon) {
        return jdbc.queryForObject("""
                INSERT INTO poi (osm_id, osm_type, name_tr, name_en, category,
                                 location, completeness_score, attributes)
                VALUES (?, 'N', ?, ?, ?,
                        ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                        80, '{}'::jsonb)
                RETURNING id
                """, String.class, osmId, name, name, TEST_CAT, lon, lat);
    }

    private JsonNode postFeedback(Object body) {
        ResponseEntity<String> resp = client.post()
                .uri("/api/pois/feedback")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(String.class);
        try {
            return jackson.readTree(resp.getBody());
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse feedback response", e);
        }
    }

    private ResponseEntity<String> postFeedbackRaw(Object body) {
        return client.post()
                .uri("/api/pois/feedback")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(String.class);
    }

    // ── Happy-path tests ───────────────────────────────────────────────────

    @Test
    void feedback_closed_returnsAcceptedAndAlternative() {
        JsonNode body = postFeedback(Map.of(
                "poiId", reportedPoiId,
                "type", "CLOSED",
                "details", "permanently closed"));

        assertThat(body.get("accepted").asBoolean()).isTrue();
        assertThat(body.get("message").asText()).isNotEmpty();
        assertThat(body.get("alternativePoi")).isNotNull();
        assertThat(body.get("alternativePoi").get("id").asText())
                .isEqualTo(alternativePoiId);
    }

    @Test
    void feedback_closed_reportedPoiExcludedFromNearby() {
        // Mark as closed first.
        ResponseEntity<String> feedbackResp = postFeedbackRaw(
                Map.of("poiId", reportedPoiId, "type", "CLOSED"));
        assertThat(feedbackResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode pois = parse(client.get()
                .uri("/api/pois/nearby?lat={lat}&lon={lon}&radiusKm=1&size=50",
                        TEST_LAT, TEST_LON)
                .retrieve()
                .body(String.class));
        assertThat(pois).isNotNull();

        for (JsonNode poi : pois) {
            assertThat(poi.get("id").asText())
                    .as("Closed POI should be excluded from nearby results")
                    .isNotEqualTo(reportedPoiId);
        }
    }

    @Test
    void feedback_duplicate_returnsAcceptedWithAlternative() {
        JsonNode body = postFeedback(Map.of(
                "poiId", reportedPoiId,
                "type", "DUPLICATE",
                "details", "same as another entry"));

        assertThat(body.get("accepted").asBoolean()).isTrue();
        assertThat(body.get("alternativePoi")).isNotNull();
    }

    @Test
    void feedback_inaccurate_returnsAcceptedAndPoiStillListed() {
        JsonNode body = postFeedback(Map.of(
                "poiId", reportedPoiId,
                "type", "INACCURATE",
                "details", "wrong opening hours"));

        assertThat(body.get("accepted").asBoolean()).isTrue();

        // INACCURATE does NOT exclude from results — POI still appears in nearby.
        JsonNode pois = parse(client.get()
                .uri("/api/pois/nearby?lat={lat}&lon={lon}&radiusKm=1&size=50",
                        TEST_LAT, TEST_LON)
                .retrieve()
                .body(String.class));

        boolean stillListed = false;
        for (JsonNode poi : pois) {
            if (poi.get("id").asText().equals(reportedPoiId)) {
                stillListed = true;
                break;
            }
        }
        assertThat(stillListed)
                .as("INACCURATE POI should still appear in nearby results")
                .isTrue();
    }

    @Test
    void feedback_moved_withCoords_returnsAcceptedAndAlternative() {
        JsonNode body = postFeedback(Map.of(
                "poiId", reportedPoiId,
                "type", "MOVED",
                "newLat", 40.9999,
                "newLon", 28.9600));

        assertThat(body.get("accepted").asBoolean()).isTrue();
        assertThat(body.get("alternativePoi")).isNotNull();
    }

    // ── Error / validation tests ────────────────────────────────────────────

    @Test
    void feedback_unknownPoiId_returns404() {
        ResponseEntity<String> resp = postFeedbackRaw(
                Map.of("poiId", UUID.randomUUID().toString(), "type", "CLOSED"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void feedback_missingType_returns400() {
        ResponseEntity<String> resp = postFeedbackRaw(Map.of("poiId", reportedPoiId));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void feedback_missingPoiId_returns400() {
        ResponseEntity<String> resp = postFeedbackRaw(Map.of("type", "CLOSED"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private JsonNode parse(String json) {
        try {
            return jackson.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse JSON: " + json, e);
        }
    }
}
