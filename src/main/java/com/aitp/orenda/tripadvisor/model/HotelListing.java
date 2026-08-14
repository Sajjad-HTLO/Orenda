package com.aitp.orenda.tripadvisor.model;

import lombok.Builder;

@Builder
public record HotelListing(
        long tripadvisorId,
        String url,
        String name,
        String sourceListingUrl
) {
}
