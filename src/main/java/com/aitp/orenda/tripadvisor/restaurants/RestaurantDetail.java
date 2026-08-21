package com.aitp.orenda.tripadvisor.restaurants;

import lombok.Builder;

import java.util.List;

/**
 * Detailed data extracted from an individual Tripadvisor restaurant review page
 * (Stage 2 of the restaurant crawler). Mapped onto the shared {@code poi} model
 * before persistence; image URLs feed the binary image download stage.
 */
@Builder
public record RestaurantDetail(
        long tripadvisorId,
        String url,
        String name,
        String address,
        String locality,
        String country,
        String postalCode,
        Double latitude,
        Double longitude,
        Double rating,
        Integer reviewCount,
        String priceRange,
        String cuisine,
        String phone,
        String description,
        List<String> imageUrls,
        String sourceListingUrl
) {
}