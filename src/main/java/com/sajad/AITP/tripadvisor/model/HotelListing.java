package com.sajad.AITP.tripadvisor.model;

import lombok.Builder;

@Builder
public record HotelListing(
        long tripadvisorId,
        String url,
        String name,
        String sourceListingUrl
) {
}
