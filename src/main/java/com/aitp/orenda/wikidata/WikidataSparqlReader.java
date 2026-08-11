package com.aitp.orenda.wikidata;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamException;
import org.springframework.batch.infrastructure.item.ItemStreamReader;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring Batch {@link ItemStreamReader} that fetches tourist POIs from Wikidata SPARQL.
 * <p>
 * On {@link #open}, it executes all configured category SPARQL queries against the
 * Wikidata endpoint, parses results into {@link WikidataRawPoi} objects, and feeds
 * them one-by-one via {@link #read()}.
 * <p>
 * Rate limiting: waits 2 seconds between queries to stay under Wikidata's anonymous
 * rate limit (~30 req/min).
 */
@Slf4j
@Component
public class WikidataSparqlReader implements ItemStreamReader<WikidataRawPoi> {

    private final WikidataClient wikidataClient;

    private List<WikidataRawPoi> pois;
    private int index;

    public WikidataSparqlReader(WikidataClient wikidataClient) {
        this.wikidataClient = wikidataClient;
    }

    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        index = executionContext.getInt("wikidata.sparql.reader.index", 0);
        try {
            pois = fetchAllCategories();
            log.info("Wikidata SPARQL reader: fetched {} total POIs across all categories, resuming from index {}",
                    pois.size(), index);
        } catch (Exception e) {
            throw new ItemStreamException("Failed to fetch POIs from Wikidata SPARQL", e);
        }
    }

    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        executionContext.putInt("wikidata.sparql.reader.index", index);
    }

    @Override
    public void close() throws ItemStreamException {
        pois = null;
    }

    @Override
    public WikidataRawPoi read() {
        if (pois == null || index >= pois.size()) return null;
        return pois.get(index++);
    }

    // ── Core fetching logic ───────────────────────────────────────────────────

    private List<WikidataRawPoi> fetchAllCategories() throws InterruptedException {
        List<WikidataClient.CategoryQuery> queries = wikidataClient.getCategoryQueries();
        List<WikidataRawPoi> allPois = new ArrayList<>();

        log.info("Starting Wikidata SPARQL fetch for {} categories", queries.size());

        for (int i = 0; i < queries.size(); i++) {
            WikidataClient.CategoryQuery cq = queries.get(i);
            log.info("[{}/{}] Querying Wikidata: category={} subcategory={}",
                    i + 1, queries.size(), cq.category(), cq.subcategory());

            List<WikidataRawPoi> categoryPois = fetchCategory(cq);
            allPois.addAll(categoryPois);

            log.info("[{}/{}] Fetched {} POIs for category={} subcategory={} (total so far: {})",
                    i + 1, queries.size(), categoryPois.size(),
                    cq.category(), cq.subcategory(), allPois.size());

            // Rate limit: wait between queries
            if (i < queries.size() - 1) {
                Thread.sleep(2_000);
            }
        }

        log.info("Wikidata SPARQL fetch complete: {} total POIs across {} categories",
                allPois.size(), queries.size());
        return allPois;
    }

    private List<WikidataRawPoi> fetchCategory(WikidataClient.CategoryQuery cq)
            throws InterruptedException {
        List<WikidataRawPoi> result = new ArrayList<>();

        List<java.util.Map<String, WikidataSparqlResponse.Binding>> bindings =
                wikidataClient.executeQuery(cq.sparql());

        for (var binding : bindings) {
            wikidataClient.parseBinding(binding, cq.category(), cq.subcategory())
                    .ifPresent(result::add);
        }

        return result;
    }
}