package com.aitp.orenda.integration;

import com.aitp.orenda.routing.RouteResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RoutingApiIntegrationTest {

    private static final double FROM_LAT = 41.008;
    private static final double FROM_LON = 28.978;
    private static final double TO_LAT = 41.011;
    private static final double TO_LON = 28.983;

    @LocalServerPort
    private int port;

    private RestTestClient client;

    @BeforeEach
    void setUp() {
        client = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"driving", "foot", "bike"})
    void getRoute_callsOsrmAndReturnsGeoJson(String profile) {
        RouteResponse route = client.get()
                .uri("/api/route?fromLat={fromLat}&fromLon={fromLon}&toLat={toLat}&toLon={toLon}&profile={profile}",
                        FROM_LAT, FROM_LON, TO_LAT, TO_LON, profile)
                .exchange()
                .expectStatus().isOk()
                .expectBody(RouteResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(route).isNotNull();
        assertThat(route.profile()).isEqualTo(profile);
        assertThat(route.distanceKm()).isGreaterThan(0);
        assertThat(route.durationMinutes()).isGreaterThan(0);
        assertThat(route.geometry()).containsEntry("type", "LineString");

        @SuppressWarnings("unchecked")
        List<?> coordinates = (List<?>) route.geometry().get("coordinates");
        assertThat(coordinates).isNotEmpty();
    }

    @Test
    void getRoute_longDistance_drivingProfile() {
        RouteResponse route = client.get()
                .uri("/api/route?fromLat=41.01&fromLon=28.97&toLat=36.89&toLon=30.71&profile=driving")
                .exchange()
                .expectStatus().isOk()
                .expectBody(RouteResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(route).isNotNull();
        assertThat(route.distanceKm()).isGreaterThan(100);
        assertThat(route.durationMinutes()).isGreaterThan(60);
    }
}
