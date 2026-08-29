package com.aitp.orenda.trip;

import com.aitp.orenda.routing.RouteResponse;
import com.aitp.orenda.routing.RoutingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TravelTimeEstimatorTest {

    // Istanbul → Ankara great-circle distance is ~350 km.
    private static final double ISTANBUL_LAT = 41.0082, ISTANBUL_LON = 28.9784;
    private static final double ANKARA_LAT = 39.9334, ANKARA_LON = 32.8597;

    @Mock
    private RoutingService routingService;

    private TravelTimeEstimator estimator;

    @BeforeEach
    void setUp() {
        estimator = new TravelTimeEstimator(routingService);
    }

    @Test
    void haversineKm_reports_real_world_distances() {
        double km = TravelTimeEstimator.haversineKm(ISTANBUL_LAT, ISTANBUL_LON, ANKARA_LAT, ANKARA_LON);
        assertThat(km).isCloseTo(351.0, within(15.0));
    }

    @Test
    void haversineKm_is_symmetric_and_zero_for_identical_points() {
        assertThat(TravelTimeEstimator.haversineKm(41, 29, 41, 29)).isZero();
        assertThat(TravelTimeEstimator.haversineKm(41, 29, 39, 33))
                .isEqualTo(TravelTimeEstimator.haversineKm(39, 33, 41, 29));
    }

    @Test
    void minutesBetween_uses_routing_result_when_available() {
        when(routingService.getRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyString()))
                .thenReturn(new RouteResponse(10, 30.0, "driving", "D100", Map.of()));

        double minutes = estimator.minutesBetween(ISTANBUL_LAT, ISTANBUL_LON, ANKARA_LAT, ANKARA_LON,
                TripEnums.TransportMode.DRIVING);

        assertThat(minutes).isEqualTo(30.0);
    }

    @Test
    void minutesBetween_falls_back_to_straight_line_when_routing_fails() {
        when(routingService.getRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyString()))
                .thenThrow(new RuntimeException("no network"));

        double km = TravelTimeEstimator.haversineKm(ISTANBUL_LAT, ISTANBUL_LON, ANKARA_LAT, ANKARA_LON);
        double expectedFoot = km / 4.5 * 60.0;

        double minutes = estimator.minutesBetween(ISTANBUL_LAT, ISTANBUL_LON, ANKARA_LAT, ANKARA_LON,
                TripEnums.TransportMode.FOOT);

        assertThat(minutes).isCloseTo(expectedFoot, within(0.001));
    }

    @Test
    void minutesBetween_maps_transport_mode_to_osrm_profile() {
        when(routingService.getRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyString()))
                .thenReturn(new RouteResponse(1, 5.0, "bike", "", Map.of()));

        estimator.minutesBetween(ISTANBUL_LAT, ISTANBUL_LON, ANKARA_LAT, ANKARA_LON, TripEnums.TransportMode.BIKE);
        verify(routingService).getRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq("bike"));

        // TRANSIT/TAXI fall back to the driving profile.
        estimator.minutesBetween(ISTANBUL_LAT, ISTANBUL_LON, ANKARA_LAT, ANKARA_LON, TripEnums.TransportMode.TRANSIT);
        verify(routingService).getRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq("driving"));
    }

    @Test
    void minutesBetween_caches_results() {
        when(routingService.getRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyString()))
                .thenReturn(new RouteResponse(10, 30.0, "driving", "D100", Map.of()));

        estimator.minutesBetween(ISTANBUL_LAT, ISTANBUL_LON, ANKARA_LAT, ANKARA_LON, TripEnums.TransportMode.DRIVING);
        estimator.minutesBetween(ISTANBUL_LAT, ISTANBUL_LON, ANKARA_LAT, ANKARA_LON, TripEnums.TransportMode.DRIVING);

        verify(routingService, times(1)).getRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq("driving"));
    }

    @Test
    void distanceKm_delegates_to_haversine() {
        assertThat(estimator.distanceKm(ISTANBUL_LAT, ISTANBUL_LON, ANKARA_LAT, ANKARA_LON))
                .isEqualTo(TravelTimeEstimator.haversineKm(ISTANBUL_LAT, ISTANBUL_LON, ANKARA_LAT, ANKARA_LON));
    }
}