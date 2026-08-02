package com.sajad.AITP.tripadvisor.crawler;

import com.sajad.AITP.tripadvisor.config.TripadvisorCrawlerProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaginationGeneratorTest {

    private final PaginationGenerator generator = new PaginationGenerator(new TripadvisorCrawlerProperties(
            true,
            "https://www.tripadvisor.com/Hotels-g293974-Istanbul-Hotels.html",
            2,
            30,
            1,
            false,
            0,
            0,
            1000,
            true,
            "test-agent"
    ));

    @Test
    void pageForOffsetZeroUsesBaseUrl() {
        assertThat(generator.pageForOffset(0).url())
                .isEqualTo("https://www.tripadvisor.com/Hotels-g293974-Istanbul-Hotels.html");
    }

    @Test
    void pageForOffsetGeneratesTripadvisorOffsetUrl() {
        assertThat(generator.pageForOffset(60).url())
                .isEqualTo("https://www.tripadvisor.com/Hotels-g293974-oa60-Istanbul-Hotels.html");
    }

    @Test
    void nextOffsetUsesConfiguredPageSize() {
        assertThat(generator.nextOffset(30)).isEqualTo(60);
    }
}
