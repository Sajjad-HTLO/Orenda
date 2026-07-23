package com.sajad.AITP.wikidata;

import lombok.Builder;
import lombok.Data;

/**
 * Raw POI extracted from a Wikidata SPARQL result row.
 * Represents one Wikidata entity (item) with geo-coordinates and tourist-relevant attributes.
 */
@Data
@Builder
public class WikidataRawPoi {

    /**
     * Wikidata Q-ID, e.g. "Q12345"
     */
    private String qid;

    /**
     * Label in Turkish (may be null)
     */
    private String labelTr;

    /**
     * Label in English (may be null)
     */
    private String labelEn;

    /**
     * Latitude (WGS84)
     */
    private double lat;

    /**
     * Longitude (WGS84)
     */
    private double lon;

    /**
     * Wikidata item description in Turkish
     */
    private String descriptionTr;

    /**
     * Wikidata item description in English
     */
    private String descriptionEn;

    /**
     * Image URL from Wikimedia Commons (P18), may be null
     */
    private String imageUrl;

    /**
     * The category of this POI, e.g., "museum", "castle", "mosque"
     */
    private String category;

    /**
     * The subcategory if applicable
     */
    private String subcategory;

    /**
     * Wikipedia article title (English), if linked via P625 coordinates extraction
     */
    private String wikipediaEnTitle;

    /**
     * Wikipedia article title (Turkish), if linked
     */
    private String wikipediaTrTitle;

    /**
     * Instance-of QID, e.g. "Q33506" (museum) — for reference
     */
    private String instanceOfQid;
}