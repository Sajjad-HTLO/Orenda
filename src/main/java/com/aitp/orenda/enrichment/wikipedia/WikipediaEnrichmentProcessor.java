package com.aitp.orenda.enrichment.wikipedia;

import com.aitp.orenda.enrichment.PoiEnrichmentCandidate;
import com.aitp.orenda.enrichment.PoiEnrichmentResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class WikipediaEnrichmentProcessor implements ItemProcessor<PoiEnrichmentCandidate, PoiEnrichmentResult> {

    private final WikipediaClient wikipediaClient;

    @Override
    public PoiEnrichmentResult process(PoiEnrichmentCandidate poi) {
        try {
            Optional<WikipediaSummary> summary = wikipediaClient.lookup(poi);
            if (summary.isEmpty()) {
                log.debug("No Wikipedia match for POI {} ({})", poi.getId(), poi.getNameTr());
                return notFound(poi);
            }
            return toResult(poi, summary.get());
        } catch (Exception e) {
            log.warn("Wikipedia enrichment failed for POI {} ({}): {}",
                    poi.getId(), poi.getNameTr(), e.getMessage());
            return notFound(poi);
        }
    }

    private PoiEnrichmentResult toResult(PoiEnrichmentCandidate poi, WikipediaSummary wiki) {
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("wikipedia_title", wiki.title());
        updates.put("wikipedia_lang", wiki.lang());
        updates.put("wikipedia_summary", wiki.extract());
        updates.put("wikipedia_enriched_at", Instant.now().toString());

        if (wiki.description() != null) updates.put("wikipedia_description", wiki.description());
        if (wiki.pageUrl() != null) updates.put("wikipedia_url", wiki.pageUrl());
        if (wiki.imageUrl() != null) updates.put("wikipedia_image_url", wiki.imageUrl());

        List<PoiEnrichmentResult.SourceField> sourceFields = new ArrayList<>();
        updates.forEach((field, value) -> sourceFields.add(
                PoiEnrichmentResult.SourceField.builder().field(field).value(String.valueOf(value)).build()
        ));

        short previousScore = poi.getCompletenessScore();
        short newScore = bumpCompleteness(previousScore, wiki);

        WikipediaImprovementDescriber.ImprovementDetails improvements = WikipediaImprovementDescriber.describe(
                poi, updates, previousScore, newScore, sourceFields.size());

        log.info("Enriched POI {} ({}) from {} Wikipedia: {} — added {}; completeness {}→{}; {} source fields stored",
                poi.getId(),
                poi.getNameTr(),
                wiki.lang(),
                wiki.title(),
                improvements.humanReadable(),
                improvements.previousScore(),
                improvements.newScore(),
                improvements.sourceFieldsStored());

        return PoiEnrichmentResult.builder()
                .poiId(poi.getId())
                .poiName(poi.getNameTr())
                .found(true)
                .lang(wiki.lang())
                .attributeUpdates(updates)
                .sourceFields(sourceFields)
                .completenessScore(newScore)
                .previousCompletenessScore(previousScore)
                .improvementSummary(improvements.humanReadable())
                .summariesAdded(improvements.summariesAdded())
                .descriptionsAdded(improvements.descriptionsAdded())
                .imagesAdded(improvements.imagesAdded())
                .urlsAdded(improvements.urlsAdded())
                .build();
    }

    private PoiEnrichmentResult notFound(PoiEnrichmentCandidate poi) {
        return PoiEnrichmentResult.builder()
                .poiId(poi.getId())
                .poiName(poi.getNameTr())
                .found(false)
                .attributeUpdates(Map.of())
                .sourceFields(List.of(
                        PoiEnrichmentResult.SourceField.builder()
                                .field("_status")
                                .value("not_found")
                                .build()
                ))
                .completenessScore(poi.getCompletenessScore())
                .previousCompletenessScore(poi.getCompletenessScore())
                .improvementSummary("no match")
                .build();
    }

    private short bumpCompleteness(short current, WikipediaSummary wiki) {
        int score = current;
        if (wiki.extract() != null && !wiki.extract().isBlank()) score += 10;
        if (wiki.imageUrl() != null) score += 5;
        return (short) Math.min(score, 100);
    }
}
