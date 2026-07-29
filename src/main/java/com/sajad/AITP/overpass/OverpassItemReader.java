package com.sajad.AITP.overpass;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamException;
import org.springframework.batch.infrastructure.item.ItemStreamReader;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring Batch {@link ItemStreamReader} that fetches tourist POIs from the Overpass API.
 * <p>
 * On {@link #open}, it executes all configured category Overpass QL queries against the
 * Overpass endpoint, parses results into {@link OverpassRawPoi} objects, and feeds
 * them one-by-one via {@link #read()}.
 * <p>
 * Rate limiting: waits 5 seconds between queries to stay under Overpass's fair-use
 * limits (~10,000 requests/day).
 */
@Slf4j
@Component
public class OverpassItemReader implements ItemStreamReader<OverpassRawPoi> {

    private final OverpassClient overpassClient;

    private List<OverpassRawPoi> pois;
    private int index;

    public OverpassItemReader(OverpassClient overpassClient) {
        this.overpassClient = overpassClient;
    }

    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        index = executionContext.getInt("overpass.reader.index", 0);
        try {
            pois = fetchAllCategories();
            log.info("Overpass reader: fetched {} total POIs across all categories, resuming from index {}",
                    pois.size(), index);
        } catch (Exception e) {
            throw new ItemStreamException("Failed to fetch POIs from Overpass API", e);
        }
    }

    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        executionContext.putInt("overpass.reader.index", index);
    }

    @Override
    public void close() throws ItemStreamException {
        pois = null;
    }

    @Override
    public OverpassRawPoi read() {
        if (pois == null || index >= pois.size()) return null;
        return pois.get(index++);
    }

    // ── Core fetching logic ───────────────────────────────────────────────────

    private List<OverpassRawPoi> fetchAllCategories() throws InterruptedException {
        List<OverpassClient.CategoryQuery> queries = overpassClient.getCategoryQueries();
        List<OverpassRawPoi> allPois = new ArrayList<>();

        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║  OVERPASS API IMPORT — {} categories to fetch", queries.size());
        log.info("╚══════════════════════════════════════════════════════════════╝");

        for (int i = 0; i < queries.size(); i++) {
            OverpassClient.CategoryQuery cq = queries.get(i);
            log.info("┌─ [{}/{}] tag={}  →  {}/{}",
                    i + 1, queries.size(), cq.label(), cq.category(), cq.subcategory());

            List<OverpassRawPoi> categoryPois = fetchCategory(cq);
            allPois.addAll(categoryPois);

            log.info("└─ [{}/{}] ✅ {} POIs  │  running total: {}",
                    i + 1, queries.size(), categoryPois.size(), allPois.size());

            // Rate limit: wait 5 seconds between queries to be polite
            if (i < queries.size() - 1) {
                log.debug("  ⏸  waiting 5s (rate limit)...");
                Thread.sleep(5_000);
            }
        }

        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║  OVERPASS FETCH COMPLETE: {} total POIs across {} categories",
                allPois.size(), queries.size());
        log.info("╚══════════════════════════════════════════════════════════════╝");
        return allPois;
    }

    private List<OverpassRawPoi> fetchCategory(OverpassClient.CategoryQuery cq)
            throws InterruptedException {
        List<OverpassRawPoi> result = new ArrayList<>();

        List<JsonNode> elements = overpassClient.executeQuery(cq.overpassQL());

        for (JsonNode element : elements) {
            overpassClient.parseElement(element, cq)
                    .ifPresent(result::add);
        }

        return result;
    }
}