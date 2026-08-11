package com.aitp.orenda.tripadvisor.model;

import lombok.Builder;

import java.util.List;

/**
 * Detailed data extracted from an individual Tripadvisor hotel review page
 * (Stage 2 of the crawler). Mapped onto the shared {@code poi} model before
 * persistence.
 */
@Builder
public record HotelDetail(
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
        String starRating,
        String phone,
        String description,
        List<String> imageUrls,
        String sourceListingUrl
) {
}
