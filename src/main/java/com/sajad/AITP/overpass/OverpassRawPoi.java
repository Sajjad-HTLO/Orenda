package com.sajad.AITP.overpass;

import lombok.Builder;
import lombok.Data;

/**
 * Raw POI extracted from an Overpass API JSON response element.
 * <p>
 * Overpass returns OSM elements (nodes, ways, relations) with tags and geometry.
 * This DTO captures the fields relevant for tourist POI enrichment.
 */
@Data
@Builder
public class OverpassRawPoi {

    /**
     * OSM element ID (positive long, as assigned by OpenStreetMap).
     */
    private long osmId;

    /**
     * OSM element type: "node", "way", "relation".
     */
    private String elementType;

    /**
     * Latitude (WGS84). For ways/relations this is the centroid.
     */
    private double lat;

    /**
     * Longitude (WGS84). For ways/relations this is the centroid.
     */
    private double lon;

    /**
     * POI name from OSM tags (name=* or name:tr=* or name:en=*).
     */
    private String nameTr;

    /**
     * English name from OSM tags (name:en=*), may be null.
     */
    private String nameEn;

    /**
     * The category this POI belongs to, e.g., "culture", "historic", "nature".
     */
    private String category;

    /**
     * The subcategory if applicable, e.g., "museum", "castle", "mosque".
     */
    private String subcategory;

    /**
     * The raw OSM tag key that determined the category (e.g., "tourism", "historic", "amenity").
     */
    private String osmTagKey;

    /**
     * The raw OSM tag value that determined the subcategory (e.g., "museum", "castle").
     */
    private String osmTagValue;

    /**
     * Wikidata ID from OSM tags (wikidata=*), may be null.
     */
    private String wikidataId;

    /**
     * Wikipedia article from OSM tags (wikipedia=*), may be null.
     */
    private String wikipediaTag;

    /**
     * Overpass API query label that produced this POI (for traceability).
     */
    private String queryLabel;
}
