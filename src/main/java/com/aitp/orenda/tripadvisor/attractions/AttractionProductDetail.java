package com.aitp.orenda.tripadvisor.attractions;

import lombok.Builder;

import java.util.List;

/**
 * Detailed data extracted from an individual Tripadvisor attraction product
 * page ({@code AttractionProductReview-}, Stage 2 of the attraction products
 * crawler). Mapped onto the shared {@code poi} model before persistence;
 * image URLs feed the binary image download stage.
 */
@Builder
public record AttractionProductDetail(
        long tripadvisorId,
        String url,
        String name,
        Double latitude,
        Double longitude,
        Double rating,
        Integer reviewCount,
        String price,
        String cost,
        String duration,
        String cancellationPolicy,
        String description,
        List<String> imageUrls,
        String sourceListingUrl
) {
}
