package com.sajad.AITP.muzegov;

import lombok.Builder;
import lombok.Data;

/**
 * Raw museum/site POI extracted from muze.gov.tr HTML scraping.
 * Each entry represents one museum or archaeological site managed by the
 * Turkish Ministry of Culture and Tourism.
 */
@Data
@Builder
public class MuzeGovTrRawPoi {

    /**
     * Unique SectionId from muze.gov.tr, e.g. "ATM01"
     */
    private String sectionId;

    /**
     * District ID from muze.gov.tr, e.g. "MRK"
     */
    private String distId;

    /**
     * Museum name in Turkish (from clusterCaption or page title)
     */
    private String nameTr;

    /**
     * Latitude (WGS84)
     */
    private double lat;

    /**
     * Longitude (WGS84)
     */
    private double lon;

    /**
     * Longer description from detail page, may be null
     */
    private String descriptionTr;
}