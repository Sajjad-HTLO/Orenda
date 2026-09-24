package com.aitp.orenda.tripadvisor.crawler;

import com.aitp.orenda.tripadvisor.config.TripadvisorCrawlerProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TripadvisorPaginationGeneratorTest {

    private static final String HOTEL_BASE =
            "https://www.tripadvisor.com/Hotels-g293974-Istanbul-Hotels.html";
    private static final String RESTAURANT_BASE =
            "https://www.tripadvisor.com/Restaurants-g293974-Istanbul.html";
    private static final String ATTRACTION_BASE =
            "https://www.tripadvisor.com/Attractions-g293974-Activities-c26-t143-Istanbul.html";
    private static final String TOUR_PRODUCT_BASE =
            "https://www.tripadvisor.com/Attraction_Products-g293974-zfg11866-Istanbul.html";

    private TripadvisorPaginationGenerator generator(String baseUrl) {
        return new TripadvisorPaginationGenerator(new TripadvisorCrawlerProperties(
                true, baseUrl, 1, 30, 1, false,
                600, 30, 10, 180_000, false, 25, true, "dinner_cruise",
                2500, 7000, 60000, true, "Mozilla/5.0",
                "data/tripadvisor-browser-profile", true));
    }

    @Test
    void pageUrlForOffsetZeroUsesBaseUrl() {
        assertThat(generator(HOTEL_BASE).pageUrlForOffset(0)).isEqualTo(HOTEL_BASE);
    }

    @Test
    void pageUrlForOffsetGeneratesTripadvisorOffsetUrlForHotels() {
        assertThat(generator(HOTEL_BASE).pageUrlForOffset(60))
                .isEqualTo("https://www.tripadvisor.com/Hotels-g293974-oa60-Istanbul-Hotels.html");
    }

    @Test
    void pageUrlForOffsetInjectsOaSegmentForRestaurants() {
        TripadvisorPaginationGenerator gen = generator(RESTAURANT_BASE);
        assertThat(gen.pageUrlForOffset(0)).isEqualTo(RESTAURANT_BASE);
        assertThat(gen.pageUrlForOffset(30))
                .isEqualTo("https://www.tripadvisor.com/Restaurants-g293974-oa30-Istanbul.html");
        assertThat(gen.pageUrlForOffset(60))
                .isEqualTo("https://www.tripadvisor.com/Restaurants-g293974-oa60-Istanbul.html");
    }

    @Test
    void pageUrlForOffsetInjectsOaSegmentBeforeCityForAttractionCategory() {
        TripadvisorPaginationGenerator gen = generator(ATTRACTION_BASE);
        assertThat(gen.pageUrlForOffset(30))
                .isEqualTo("https://www.tripadvisor.com/Attractions-g293974-Activities-c26-t143-oa30-Istanbul.html");
        assertThat(gen.pageUrlForOffset(90))
                .isEqualTo("https://www.tripadvisor.com/Attractions-g293974-Activities-c26-t143-oa90-Istanbul.html");
    }

    @Test
    void pageUrlForOffsetReplacesExistingOaSegment() {
        TripadvisorPaginationGenerator gen =
                generator("https://www.tripadvisor.com/Hotels-g293974-oa6990-Istanbul-Hotels.html");
        assertThat(gen.pageUrlForOffset(7020))
                .isEqualTo("https://www.tripadvisor.com/Hotels-g293974-oa7020-Istanbul-Hotels.html");
        assertThat(gen.pageUrlForOffset(7020, "https://www.tripadvisor.com/Restaurants-g293974-oa30-Istanbul.html"))
                .isEqualTo("https://www.tripadvisor.com/Restaurants-g293974-oa7020-Istanbul.html");
    }

    @Test
    void pageUrlForOffsetZeroRemovesOaSegment() {
        assertThat(generator("https://www.tripadvisor.com/Restaurants-g293974-oa30-Istanbul.html").pageUrlForOffset(0))
                .isEqualTo(RESTAURANT_BASE);
        assertThat(generator("https://www.tripadvisor.com/Hotels-g293974-oa6990-Istanbul-Hotels.html").pageUrlForOffset(0))
                .isEqualTo(HOTEL_BASE);
    }

    @Test
    void explicitBaseUrlOverloadInjectsOffset() {
        TripadvisorPaginationGenerator gen = generator(TOUR_PRODUCT_BASE);
        assertThat(gen.pageUrlForOffset(30, TOUR_PRODUCT_BASE))
                .isEqualTo("https://www.tripadvisor.com/Attraction_Products-g293974-zfg11866-oa30-Istanbul.html");
    }

    @Test
    void baseOffsetParsesFromUrl() {
        assertThat(generator("https://www.tripadvisor.com/Restaurants-g293974-oa30-Istanbul.html").baseOffset())
                .isEqualTo(30);
        assertThat(generator(RESTAURANT_BASE).baseOffset()).isZero();
        assertThat(generator("https://www.tripadvisor.com/Attractions-g293974-Activities-c26-t143-oa90-Istanbul.html").baseOffset())
                .isEqualTo(90);
        assertThat(generator("https://www.tripadvisor.com/Hotels-g293974-oa6990-Istanbul-Hotels.html").baseOffset())
                .isEqualTo(6990);
    }

    @Test
    void nextOffsetUsesConfiguredPageSize() {
        TripadvisorPaginationGenerator gen = generator(RESTAURANT_BASE);
        assertThat(gen.nextOffset(0)).isEqualTo(30);
        assertThat(gen.nextOffset(30)).isEqualTo(60);
        assertThat(gen.nextOffset(60)).isEqualTo(90);
    }
}