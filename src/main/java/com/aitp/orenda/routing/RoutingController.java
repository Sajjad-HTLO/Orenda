package com.aitp.orenda.routing;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/route")
@RequiredArgsConstructor
public class RoutingController {

    private final RoutingService routingService;

    /**
     * Point-to-point route between two coordinates.
     * GET /api/route?fromLat=41.01&fromLon=28.97&toLat=36.89&toLon=30.71&profile=driving
     * <p>
     * profile: driving (default) | foot | bike
     * <p>
     * Powered by OSRM public demo server (https://project-osrm.org) — free, no API key required.
     * Returns distance (km), duration (minutes), road summary, and GeoJSON LineString geometry.
     */
    @GetMapping
    public RouteResponse getRoute(
            @RequestParam double fromLat,
            @RequestParam double fromLon,
            @RequestParam double toLat,
            @RequestParam double toLon,
            @RequestParam(defaultValue = "driving") String profile) {

        return routingService.getRoute(fromLat, fromLon, toLat, toLon, profile);
    }
}
