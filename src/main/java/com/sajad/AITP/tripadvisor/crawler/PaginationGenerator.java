package com.sajad.AITP.tripadvisor.crawler;

import com.sajad.AITP.tripadvisor.config.TripadvisorCrawlerProperties;
import com.sajad.AITP.tripadvisor.model.CrawlPage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "tripadvisor.crawler.enabled", havingValue = "true")
public class PaginationGenerator {

    private final TripadvisorCrawlerProperties properties;

    public PaginationGenerator(TripadvisorCrawlerProperties properties) {
        this.properties = properties;
    }

    public CrawlPage pageForOffset(int offset) {
        if (offset <= 0) {
            return CrawlPage.builder()
                    .offset(0)
                    .url(properties.baseUrl())
                    .build();
        }
        String baseUrl = properties.baseUrl();
        int cityMarker = baseUrl.indexOf("-Istanbul-");
        if (cityMarker < 0) {
            throw new IllegalArgumentException("Tripadvisor Istanbul hotel base URL must contain -Istanbul-: " + baseUrl);
        }
        return CrawlPage.builder()
                .offset(offset)
                .url(baseUrl.substring(0, cityMarker) + "-oa" + offset + baseUrl.substring(cityMarker))
                .build();
    }

    public int nextOffset(int offset) {
        return offset + properties.pageSize();
    }
}
