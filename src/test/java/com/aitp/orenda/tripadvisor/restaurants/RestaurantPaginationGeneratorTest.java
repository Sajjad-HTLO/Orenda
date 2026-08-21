package com.aitp.orenda.tripadvisor.restaurants;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RestaurantPaginationGeneratorTest {

    private RestaurantPaginationGenerator generator(String baseUrl) {
        RestaurantCrawlerProperties props = new RestaurantCrawlerProperties(
                true, baseUrl, 1, 30, 1, false, 2500, 7000, 60000, true,
                "Mozilla/5.0");
        return new RestaurantPaginationGenerator(props);
    }

    @Test
    void baseOffsetParsesFromUrl() {
        assertThat(generator("https://www.tripadvisor.com/Restaurants-g293974-oa30-Istanbul.html").baseOffset())
                .isEqualTo(30);
        assertThat(generator("https://www.tripadvisor.com/Restaurants-g293974-Istanbul.html").baseOffset())
                .isZero();
    }

    @Test
    void pageUrlForOffsetIncrementsByThirty() {
        RestaurantPaginationGenerator gen =
                generator("https://www.tripadvisor.com/Restaurants-g293974-oa30-Istanbul.html");
        assertThat(gen.pageUrlForOffset(30))
                .isEqualTo("https://www.tripadvisor.com/Restaurants-g293974-oa30-Istanbul.html");
        assertThat(gen.pageUrlForOffset(60))
                .isEqualTo("https://www.tripadvisor.com/Restaurants-g293974-oa60-Istanbul.html");
        assertThat(gen.pageUrlForOffset(90))
                .isEqualTo("https://www.tripadvisor.com/Restaurants-g293974-oa90-Istanbul.html");
        assertThat(gen.nextOffset(30)).isEqualTo(60);
        assertThat(gen.nextOffset(60)).isEqualTo(90);
    }

    @Test
    void pageUrlForOffsetZeroRemovesOaSegment() {
        RestaurantPaginationGenerator gen =
                generator("https://www.tripadvisor.com/Restaurants-g293974-oa30-Istanbul.html");
        assertThat(gen.pageUrlForOffset(0))
                .isEqualTo("https://www.tripadvisor.com/Restaurants-g293974-Istanbul.html");
    }

    @Test
    void injectsOaSegmentWhenAbsent() {
        RestaurantPaginationGenerator gen =
                generator("https://www.tripadvisor.com/Restaurants-g293974-Istanbul.html");
        assertThat(gen.pageUrlForOffset(30))
                .isEqualTo("https://www.tripadvisor.com/Restaurants-g293974-oa30-Istanbul.html");
    }
}