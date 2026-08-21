package com.aitp.orenda.tripadvisor.restaurants;

import java.util.List;

public record RestaurantListingParseResult(
        List<RestaurantListing> restaurants
) {

    public int restaurantCount() {
        return restaurants.size();
    }
}