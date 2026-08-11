package com.aitp.orenda.integration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PoiApiIntegrationTest {

    private static final double ISTANBUL_LAT = 41.01;
    private static final double ISTANBUL_LON = 28.97;

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    private RestTestClient client;

    @BeforeAll
    void requireImportedPoiData() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM poi", Integer.class);
        assumeTrue(count != null && count > 0,
                "Requires PostGIS with imported POI data (start aitp-pg and run the import job)");
    }

    @BeforeEach
    void setUp() {
        client = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    void categories_returnsGroupedCounts() {
        List<Map<String, Object>> categories = client.get()
                .uri("/api/pois/categories")
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<Map<String, Object>>>() {
                })
                .returnResult()
                .getResponseBody();

        assertThat(categories).isNotEmpty();
        assertThat(categories.get(0)).containsKeys("category", "subcategory", "count");
    }

    @Test
    void nearby_withoutCategoryFilter_returnsClosestPois() {
        JsonNode pois = client.get()
                .uri("/api/pois/nearby?lat={lat}&lon={lon}&radiusKm=5&size=5",
                        ISTANBUL_LAT, ISTANBUL_LON)
                .exchange()
                .expectStatus().isOk()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();

        assertThat(pois.isArray()).isTrue();
        assertThat(pois).isNotEmpty();
        assertThat(pois.get(0).get("distanceKm").asDouble()).isLessThan(5.0);
        assertThat(pois.get(0).get("lat").asDouble()).isBetween(40.0, 42.0);
        assertThat(pois.get(0).get("lon").asDouble()).isBetween(28.0, 30.0);
    }

    @Test
    void nearby_withCategoryFilter_returnsMatchingPois() {
        JsonNode pois = client.get()
                .uri("/api/pois/nearby?lat={lat}&lon={lon}&radiusKm=5&category=historic&size=5",
                        ISTANBUL_LAT, ISTANBUL_LON)
                .exchange()
                .expectStatus().isOk()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();

        assertThat(pois).isNotEmpty();
        for (JsonNode poi : pois) {
            assertThat(poi.get("category").asText()).isEqualTo("historic");
        }
    }

    @Test
    void search_findsTopkapiPalace() {
        JsonNode pois = client.get()
                .uri("/api/pois/search?q=topkapi&size=5")
                .exchange()
                .expectStatus().isOk()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();

        assertThat(pois).isNotEmpty();
        boolean found = false;
        for (JsonNode poi : pois) {
            String nameTr = poi.path("nameTr").asText("").toLowerCase();
            String nameEn = poi.path("nameEn").asText("").toLowerCase();
            if (nameTr.contains("topkapi") || nameEn.contains("topkapi")) {
                found = true;
                break;
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    void getById_returnsSinglePoi() {
        JsonNode searchResults = client.get()
                .uri("/api/pois/search?q=topkapi&size=1")
                .exchange()
                .expectStatus().isOk()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();

        assertThat(searchResults).isNotEmpty();
        String id = searchResults.get(0).get("id").asText();

        JsonNode poi = client.get()
                .uri("/api/pois/{id}", id)
                .exchange()
                .expectStatus().isOk()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();

        assertThat(poi.get("id").asText()).isEqualTo(id);
        assertThat(poi.get("attributes").isObject()).isTrue();
        assertThat(poi.get("attributes").size()).isGreaterThan(0);
    }

    @Test
    void getById_unknownId_returns404() {
        client.get()
                .uri("/api/pois/00000000-0000-0000-0000-000000000000")
                .exchange()
                .expectStatus().isNotFound();
    }
}
