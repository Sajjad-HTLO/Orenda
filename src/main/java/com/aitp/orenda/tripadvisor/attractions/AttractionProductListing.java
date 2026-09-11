package com.aitp.orenda.tripadvisor.attractions;

import lombok.Builder;

@Builder
public record AttractionProductListing(
        long tripadvisorId,
        String url,
        String name,
        String sourceListingUrl
) {
}
