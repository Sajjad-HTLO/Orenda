package com.aitp.orenda.integration;

import com.aitp.orenda.weather.WeatherResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WeatherApiIntegrationTest {

    private static final double ISTANBUL_LAT = 41.01;
    private static final double ISTANBUL_LON = 28.97;

    @LocalServerPort
    private int port;

    private RestTestClient client;

    @BeforeEach
    void setUp() {
        client = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    void getWeather_callsOpenMeteoAndReturnsForecast() {
        WeatherResponse body = client.get()
                .uri("/api/weather?lat={lat}&lon={lon}&days=5", ISTANBUL_LAT, ISTANBUL_LON)
                .exchange()
                .expectStatus().isOk()
                .expectBody(WeatherResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).isNotNull();
        assertThat(body.location().timezone()).isNotBlank();
        assertThat(body.location().latitude()).isBetween(40.0, 42.0);
        assertThat(body.location().longitude()).isBetween(28.0, 30.0);

        assertThat(body.current().description()).isNotBlank();
        assertThat(body.current().humidity()).isBetween(0, 100);

        assertThat(body.daily()).hasSize(5);
        assertThat(body.daily().get(0).date()).matches("\\d{4}-\\d{2}-\\d{2}");
        assertThat(body.daily().get(0).description()).isNotBlank();
    }

    @Test
    void getWeather_clampsDaysToValidRange() {
        WeatherResponse body = client.get()
                .uri("/api/weather?lat={lat}&lon={lon}&days=30", ISTANBUL_LAT, ISTANBUL_LON)
                .exchange()
                .expectStatus().isOk()
                .expectBody(WeatherResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).isNotNull();
        assertThat(body.daily()).hasSize(16);
    }
}
