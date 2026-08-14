package com.aitp.orenda.muzegov;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamException;
import org.springframework.batch.infrastructure.item.ItemStreamReader;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Spring Batch reader that scrapes muze.gov.tr for museum POIs.
 * <p>
 * The client fetches all museums in a single HTTP request (they're embedded
 * in the Leaflet map data on the page), so this reader simply delegates to
 * the client and iterates through the results.
 */
@Slf4j
@Component
public class MuzeGovTrItemReader implements ItemStreamReader<MuzeGovTrRawPoi> {

    private final MuzeGovTrClient client;

    private List<MuzeGovTrRawPoi> pois;
    private int index;

    public MuzeGovTrItemReader(MuzeGovTrClient client) {
        this.client = client;
    }

    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        index = executionContext.getInt("muzegov.reader.index", 0);
        try {
            pois = client.fetchAllMuseums();
            log.info("MuzeGovTr reader: scraped {} museums, resuming from index {}", pois.size(), index);
        } catch (Exception e) {
            throw new ItemStreamException("Failed to scrape muze.gov.tr", e);
        }
    }

    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        executionContext.putInt("muzegov.reader.index", index);
    }

    @Override
    public void close() throws ItemStreamException {
        pois = null;
    }

    @Override
    public MuzeGovTrRawPoi read() {
        if (pois == null || index >= pois.size()) return null;
        return pois.get(index++);
    }
}