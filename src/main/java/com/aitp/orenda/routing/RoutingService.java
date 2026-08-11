package com.aitp.orenda.routing;

import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

@Service
public class RoutingService {

    private static final String OSRM_BASE = "http://router.project-osrm.org";
    private static final Set<String> VALID_PROFILES = Set.of("driving", "foot", "bike");

    private final RestClient restClient = RestClient.builder().baseUrl(OSRM_BASE).build();

    /**
     * Returns the fastest route between two points via OSRM public demo server.
     * Coordinates are in WGS-84 (lat/lon). Profile must be driving, foot, or bike.
     */
    public RouteResponse getRoute(
            double fromLat, double fromLon,
            double toLat, double toLon,
            String profile) {

        String safeProfile = VALID_PROFILES.contains(profile) ? profile : "driving";
        // OSRM expects lon,lat order
        String coordinates = fromLon + "," + fromLat + ";" + toLon + "," + toLat;

        OsrmResponse raw = restClient.get()
                .uri(u -> u
                        .path("/route/v1/{profile}/{coords}")
                        .queryParam("overview", "full")
                        .queryParam("geometries", "geojson")
                        .queryParam("steps", "false")
                        .build(safeProfile, coordinates))
                .header("Accept-Encoding", "identity")
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    throw new ResponseStatusException(res.getStatusCode(),
                            "OSRM routing error: " + res.getStatusCode());
                })
                .body(OsrmResponse.class);

        if (raw == null || !"Ok".equals(raw.code()) || raw.routes() == null || raw.routes().isEmpty()) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "No route found");
        }

        var route = raw.routes().get(0);
        var leg = route.legs().get(0);

        double distanceKm = Math.round(route.distance() / 10.0) / 100.0; // m → km, 2 dp
        double durationMinutes = Math.round(route.duration() / 6.0) / 10.0;   // s → min, 1 dp

        return new RouteResponse(
                distanceKm,
                durationMinutes,
                safeProfile,
                leg.summary() != null ? leg.summary() : "",
                route.geometry()
        );
    }
}
