package com.aitp.orenda.tripadvisor.attractions;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AttractionProductPaginationGeneratorTest {

    private static final String BASE_URL =
            "https://www.tripadvisor.com/Attractions-g293974-Activities-c26-t143-Istanbul.html";

    private AttractionProductPaginationGenerator generator(String baseUrl) {
        AttractionProductCrawlerProperties props = new AttractionProductCrawlerProperties(
                true, baseUrl, AttractionProductPaginationMode.NEXT_PAGE_URL, 30, 1, 1, 600, 30,
                10, 180_000, 2500, 7000, 60000, true, "Mozilla/5.0", false, 25, true);
        return new AttractionProductPaginationGenerator(props);
    }

    @Test
    void baseOffsetParsesFromUrl() {
        assertThat(generator(BASE_URL).baseOffset()).isZero();
        assertThat(generator("https://www.tripadvisor.com/Attractions-g293974-Activities-c26-t143-oa30-Istanbul.html")
                .baseOffset()).isEqualTo(30);
    }

    @Test
    void injectsOaSegmentBeforeCityMarker() {
        AttractionProductPaginationGenerator gen = generator(BASE_URL);
        assertThat(gen.pageUrlForOffset(30))
                .isEqualTo("https://www.tripadvisor.com/Attractions-g293974-Activities-c26-t143-oa30-Istanbul.html");
        assertThat(gen.pageUrlForOffset(60))
                .isEqualTo("https://www.tripadvisor.com/Attractions-g293974-Activities-c26-t143-oa60-Istanbul.html");
        assertThat(gen.pageUrlForOffset(90))
                .isEqualTo("https://www.tripadvisor.com/Attractions-g293974-Activities-c26-t143-oa90-Istanbul.html");
    }

    @Test
    void pageUrlForOffsetZeroRemovesOaSegment() {
        AttractionProductPaginationGenerator gen =
                generator("https://www.tripadvisor.com/Attractions-g293974-Activities-c26-t143-oa30-Istanbul.html");
        assertThat(gen.pageUrlForOffset(0)).isEqualTo(BASE_URL);
    }

    @Test
    void nextOffsetAdvancesByPageSize() {
        AttractionProductPaginationGenerator gen = generator(BASE_URL);
        assertThat(gen.nextOffset(0)).isEqualTo(30);
        assertThat(gen.nextOffset(30)).isEqualTo(60);
        assertThat(gen.nextOffset(60)).isEqualTo(90);
    }
}
