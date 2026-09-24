package com.aitp.orenda.tripadvisor.crawler;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TripadvisorListingTypeTest {

    @Test
    void detectsAttractionCategoryListing() {
        assertThat(TripadvisorListingType.detect(
                "https://www.tripadvisor.com/Attractions-g293974-Activities-c26-t143-Istanbul.html"))
                .isEqualTo(TripadvisorListingType.ATTRACTION);
        assertThat(TripadvisorListingType.ATTRACTION.linkMarker()).isEqualTo("Attraction_Review-");
        assertThat(TripadvisorListingType.ATTRACTION.poiCategory()).isEqualTo("attraction");
    }

    @Test
    void detectsTourProductListing() {
        assertThat(TripadvisorListingType.detect(
                "https://www.tripadvisor.com/Attraction_Products-g293974-zfg11866-Istanbul.html"))
                .isEqualTo(TripadvisorListingType.TOUR_PRODUCT);
        assertThat(TripadvisorListingType.TOUR_PRODUCT.linkMarker()).isEqualTo("AttractionProductReview-");
        assertThat(TripadvisorListingType.TOUR_PRODUCT.poiCategory()).isEqualTo("activity");
    }

    @Test
    void detectsRestaurantListing() {
        assertThat(TripadvisorListingType.detect("https://www.tripadvisor.com/Restaurants-g293974-Istanbul.html"))
                .isEqualTo(TripadvisorListingType.RESTAURANT);
        assertThat(TripadvisorListingType.RESTAURANT.linkMarker()).isEqualTo("Restaurant_Review-");
        assertThat(TripadvisorListingType.RESTAURANT.poiCategory()).isEqualTo("food_drink");
    }

    @Test
    void detectsHotelListing() {
        assertThat(TripadvisorListingType.detect("https://www.tripadvisor.com/Hotels-g293974-Istanbul-Hotels.html"))
                .isEqualTo(TripadvisorListingType.HOTEL);
        assertThat(TripadvisorListingType.HOTEL.linkMarker()).isEqualTo("Hotel_Review-");
        assertThat(TripadvisorListingType.HOTEL.poiCategory()).isEqualTo("accommodation");
    }

    @Test
    void defaultsToHotelForUnknownOrNull() {
        assertThat(TripadvisorListingType.detect(null)).isEqualTo(TripadvisorListingType.HOTEL);
        assertThat(TripadvisorListingType.detect("")).isEqualTo(TripadvisorListingType.HOTEL);
        assertThat(TripadvisorListingType.detect("https://www.tripadvisor.com/"))
                .isEqualTo(TripadvisorListingType.HOTEL);
    }
}