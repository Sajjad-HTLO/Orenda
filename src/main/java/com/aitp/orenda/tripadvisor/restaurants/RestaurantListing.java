package com.aitp.orenda.tripadvisor.restaurants;

import lombok.Builder;

@Builder
public record RestaurantListing(
        long tripadvisorId,
        String url,
        String name,
        String sourceListingUrl
) {
}