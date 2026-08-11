package com.aitp.orenda.enrichment;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
public class PoiEnrichmentResult {
    private UUID poiId;
    private String poiName;
    private boolean found;
    private String lang;
    private Map<String, Object> attributeUpdates;
    private List<SourceField> sourceFields;
    private short completenessScore;
    private short previousCompletenessScore;
    private String improvementSummary;
    private int summariesAdded;
    private int descriptionsAdded;
    private int imagesAdded;
    private int urlsAdded;

    @Data
    @Builder
    public static class SourceField {
        private String field;
        private String value;
    }
}
