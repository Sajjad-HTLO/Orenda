package com.aitp.orenda.routing;

import java.util.Map;

public record RouteResponse(
        double distanceKm,
        double durationMinutes,
        String profile,
        String summary,
        Map<String, Object> geometry
) {
}