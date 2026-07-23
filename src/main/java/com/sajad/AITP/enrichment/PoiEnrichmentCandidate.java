package com.sajad.AITP.enrichment;

import lombok.Builder;
import lombok.Data;

import java.util.Map;
import java.util.UUID;

@Data
@Builder
public class PoiEnrichmentCandidate {
    private UUID id;
    private String nameTr;
    private String nameEn;
    private String wikidataId;
    private String category;
    private short completenessScore;
    private Map<String, Object> attributes;
}
