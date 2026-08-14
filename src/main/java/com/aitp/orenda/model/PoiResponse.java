package com.aitp.orenda.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PoiResponse {
    private String id;
    private long osmId;
    private String osmType;
    private String nameTr;
    private String nameEn;
    private String category;
    private String subcategory;
    private double lat;
    private double lon;
    private int completenessScore;
    private Double distanceKm;          // present only in /nearby results
    private Map<String, Object> attributes;
}
