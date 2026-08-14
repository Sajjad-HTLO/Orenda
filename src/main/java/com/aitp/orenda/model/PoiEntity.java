package com.aitp.orenda.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PoiEntity {
    private long osmId;
    private String osmType;          // "N", "W", "R"
    private String wikidataId;       // nullable
    private String nameTr;
    private String nameEn;           // nullable
    private String category;
    private String subcategory;      // nullable
    private double lat;
    private double lon;
    private String boundaryWkt;      // nullable; SRID=4326;POLYGON((...)) for closed ways
    private short completenessScore;
    private String attributesJson;
}