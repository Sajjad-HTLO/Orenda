package com.aitp.orenda.model;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class OsmPoi {
    private long osmId;
    private char osmType;        // 'N'ode, 'W'ay, 'R'elation
    private double lat;
    private double lon;
    private String boundaryWkt;  // null for nodes; SRID=4326;POLYGON((...)) for closed ways
    private Map<String, String> tags;
}