package com.aitp.orenda.tripadvisor.restaurants;

import java.util.List;

public record RestaurantCrawlResult(
        String url,
        int restaurantCount,
        boolean successful,
        String errorMessage,
        List<RestaurantListing> restaurants
) {

    public static RestaurantCrawlResult success(String url, List<RestaurantListing> restaurants) {
        return new RestaurantCrawlResult(url, restaurants.size(), true, null, restaurants);
    }

    public static RestaurantCrawlResult failed(String url, Exception error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return new RestaurantCrawlResult(url, 0, false, message, List.of());
    }
}