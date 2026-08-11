package com.aitp.orenda.routing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OsrmResponse(
        String code,
        List<Route> routes
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Route(
            double distance,
            double duration,
            Map<String, Object> geometry,
            List<Leg> legs
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Leg(
            double distance,
            double duration,
            String summary
    ) {
    }
}