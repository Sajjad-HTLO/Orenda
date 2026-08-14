package com.aitp.orenda.enrichment.wikipedia;

import com.aitp.orenda.enrichment.PoiEnrichmentResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Component
public class WikipediaEnrichmentStats {

    private final AtomicInteger processed = new AtomicInteger();
    private final AtomicInteger enriched = new AtomicInteger();
    private final AtomicInteger notFound = new AtomicInteger();
    private final AtomicInteger summariesAdded = new AtomicInteger();
    private final AtomicInteger descriptionsAdded = new AtomicInteger();
    private final AtomicInteger imagesAdded = new AtomicInteger();
    private final AtomicInteger urlsAdded = new AtomicInteger();
    private final AtomicInteger sourceFieldsStored = new AtomicInteger();

    void reset() {
        processed.set(0);
        enriched.set(0);
        notFound.set(0);
        summariesAdded.set(0);
        descriptionsAdded.set(0);
        imagesAdded.set(0);
        urlsAdded.set(0);
        sourceFieldsStored.set(0);
    }

    void record(PoiEnrichmentResult result) {
        processed.incrementAndGet();
        sourceFieldsStored.addAndGet(result.getSourceFields().size());
        if (!result.isFound()) {
            notFound.incrementAndGet();
            return;
        }
        enriched.incrementAndGet();
        summariesAdded.addAndGet(result.getSummariesAdded());
        descriptionsAdded.addAndGet(result.getDescriptionsAdded());
        imagesAdded.addAndGet(result.getImagesAdded());
        urlsAdded.addAndGet(result.getUrlsAdded());
    }

    String summarize() {
        return String.format(
                "processed=%d, enriched=%d, not_found=%d, summaries=%d, descriptions=%d, images=%d, urls=%d, source_fields=%d",
                processed.get(),
                enriched.get(),
                notFound.get(),
                summariesAdded.get(),
                descriptionsAdded.get(),
                imagesAdded.get(),
                urlsAdded.get(),
                sourceFieldsStored.get()
        );
    }

    int processedCount() {
        return processed.get();
    }

    int enrichedCount() {
        return enriched.get();
    }

    int notFoundCount() {
        return notFound.get();
    }

    int summariesCount() {
        return summariesAdded.get();
    }

    int descriptionsCount() {
        return descriptionsAdded.get();
    }

    int imagesCount() {
        return imagesAdded.get();
    }

    int urlsCount() {
        return urlsAdded.get();
    }

    int sourceFieldsCount() {
        return sourceFieldsStored.get();
    }
}
