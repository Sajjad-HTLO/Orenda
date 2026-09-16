package com.aitp.orenda.tripadvisor.attractions;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AttractionListingTypeTest {

    @Test
    void detectsAttractionCategoryListing() {
        assertThat(AttractionListingType.detect(
                "https://www.tripadvisor.com/Attractions-g293974-Activities-c26-t143-Istanbul.html"))
                .isEqualTo(AttractionListingType.ATTRACTION);
        assertThat(AttractionListingType.ATTRACTION.linkMarker()).isEqualTo("Attraction_Review-");
        assertThat(AttractionListingType.ATTRACTION.poiCategory()).isEqualTo("attraction");
    }

    @Test
    void detectsTourProductListing() {
        assertThat(AttractionListingType.detect(
                "https://www.tripadvisor.com/Attraction_Products-g293974-zfg11866-Istanbul.html"))
                .isEqualTo(AttractionListingType.TOUR_PRODUCT);
        assertThat(AttractionListingType.TOUR_PRODUCT.linkMarker()).isEqualTo("AttractionProductReview-");
        assertThat(AttractionListingType.TOUR_PRODUCT.poiCategory()).isEqualTo("activity");
    }

    @Test
    void defaultsToTourProductForUnknownOrNull() {
        assertThat(AttractionListingType.detect(null)).isEqualTo(AttractionListingType.TOUR_PRODUCT);
        assertThat(AttractionListingType.detect("")).isEqualTo(AttractionListingType.TOUR_PRODUCT);
        assertThat(AttractionListingType.detect("https://www.tripadvisor.com/Hotels-g293974-Istanbul-Hotels.html"))
                .isEqualTo(AttractionListingType.TOUR_PRODUCT);
    }
}
