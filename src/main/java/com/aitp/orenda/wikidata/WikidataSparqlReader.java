package com.aitp.orenda.wikidata;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamException;
import org.springframework.batch.infrastructure.item.ItemStreamReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring Batch {@link ItemStreamReader} that fetches tourist POIs from Wikidata SPARQL.
 * <p>
 * Fetching is streaming by category: the reader queries one category, emits its
 * items via {@link #read()}, then moves to the next category. This allows the
 * process/write pipeline to start immediately instead of waiting for all categories.
 * <p>
 * Rate limiting: waits 2 seconds between queries to stay under Wikidata's anonymous
 * rate limit (~30 req/min).
 */
@Slf4j
@Component
public class WikidataSparqlReader implements ItemStreamReader<WikidataRawPoi> {

    private static final String INDEX_KEY = "wikidata.sparql.reader.index";
    private static final String CATEGORY_INDEX_KEY = "wikidata.sparql.reader.categoryIndex";
    private static final String CATEGORY_ITEM_INDEX_KEY = "wikidata.sparql.reader.categoryItemIndex";
    private static final String RESET_FROM_START_PARAM = "resetFromStart";

    private final WikidataClient wikidataClient;
    private final long interCategoryDelayMs;

    private List<WikidataClient.CategoryQuery> queries;
    private List<WikidataRawPoi> currentCategoryPois;
    private int categoryIndex;
    private int itemIndexInCategory;
    private int emittedCount;

    public WikidataSparqlReader(
            WikidataClient wikidataClient,
            @Value("${wikidata.import.inter-category-delay-ms:2000}") long interCategoryDelayMs) {
        this.wikidataClient = wikidataClient;
        this.interCategoryDelayMs = Math.max(0, interCategoryDelayMs);
    }

    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        boolean resetFromStart = shouldResetFromStart();
        if (resetFromStart) {
            emittedCount = 0;
            categoryIndex = 0;
            itemIndexInCategory = 0;
            executionContext.putInt(INDEX_KEY, 0);
            executionContext.putInt(CATEGORY_INDEX_KEY, 0);
            executionContext.putInt(CATEGORY_ITEM_INDEX_KEY, 0);
            log.info("Wikidata SPARQL reader reset requested via job parameter; starting from index 0");
        } else {
            emittedCount = executionContext.getInt(INDEX_KEY, 0);
            categoryIndex = executionContext.getInt(CATEGORY_INDEX_KEY, 0);
            itemIndexInCategory = executionContext.getInt(CATEGORY_ITEM_INDEX_KEY, 0);
        }

        try {
            queries = wikidataClient.getCategoryQueries();
            currentCategoryPois = null;
            log.info("Wikidata SPARQL reader opened | categories={} | resume_category={} | resume_item={} | resume_index={}",
                    queries.size(), categoryIndex, itemIndexInCategory, emittedCount);
        } catch (Exception e) {
            throw new ItemStreamException("Failed to fetch POIs from Wikidata SPARQL", e);
        }
    }

    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        executionContext.putInt(INDEX_KEY, emittedCount);
        executionContext.putInt(CATEGORY_INDEX_KEY, categoryIndex);
        executionContext.putInt(CATEGORY_ITEM_INDEX_KEY, itemIndexInCategory);
    }

    @Override
    public void close() throws ItemStreamException {
        queries = null;
        currentCategoryPois = null;
    }

    @Override
    public WikidataRawPoi read() {
        try {
            while (true) {
                if (queries == null || categoryIndex >= queries.size()) {
                    return null;
                }

                if (currentCategoryPois == null) {
                    loadCurrentCategory();
                }

                if (itemIndexInCategory < currentCategoryPois.size()) {
                    emittedCount++;
                    return currentCategoryPois.get(itemIndexInCategory++);
                }

                // Move to next category after current one is exhausted.
                categoryIndex++;
                itemIndexInCategory = 0;
                currentCategoryPois = null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ItemStreamException("Interrupted while reading Wikidata categories", e);
        } catch (Exception e) {
            throw new ItemStreamException("Failed while streaming Wikidata POIs", e);
        }
    }

    // ── Core fetching logic ───────────────────────────────────────────────────

    private void loadCurrentCategory() throws InterruptedException {
        WikidataClient.CategoryQuery cq = queries.get(categoryIndex);
        if (categoryIndex > 0 && interCategoryDelayMs > 0) {
            Thread.sleep(interCategoryDelayMs);
        }

        log.info("[{}/{}] Querying Wikidata: category={} subcategory={}",
                categoryIndex + 1, queries.size(), cq.category(), cq.subcategory());

        currentCategoryPois = fetchCategory(cq);

        log.info("[{}/{}] Fetched {} POIs for category={} subcategory={} (streaming continues)",
                categoryIndex + 1, queries.size(), currentCategoryPois.size(),
                cq.category(), cq.subcategory());
    }

    private List<WikidataRawPoi> fetchCategory(WikidataClient.CategoryQuery cq)
            throws InterruptedException {
        List<WikidataRawPoi> result = new ArrayList<>();

        List<java.util.Map<String, WikidataSparqlResponse.Binding>> bindings =
                wikidataClient.executeQuery(cq.sparql(), cq.category(), cq.subcategory());

        for (var binding : bindings) {
            wikidataClient.parseBinding(binding, cq.category(), cq.subcategory())
                    .ifPresent(result::add);
        }

        return result;
    }

    private boolean shouldResetFromStart() {
        var context = StepSynchronizationManager.getContext();
        if (context == null) {
            return false;
        }

        String raw = context.getStepExecution().getJobParameters().getString(RESET_FROM_START_PARAM, "false");
        return Boolean.parseBoolean(raw);
    }
}