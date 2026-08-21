package com.aitp.orenda.tripadvisor.restaurants;

/**
 * Outcome of crawling a single restaurant detail page (Stage 2). Carries the
 * parsed detail on success and a human-readable reason on failure.
 */
public record RestaurantDetailCrawlResult(
        boolean successful,
        RestaurantDetail detail,
        String errorMessage
) {

    public static RestaurantDetailCrawlResult success(RestaurantDetail detail) {
        return new RestaurantDetailCrawlResult(true, detail, null);
    }

    public static RestaurantDetailCrawlResult failure(String errorMessage) {
        return new RestaurantDetailCrawlResult(false, null, errorMessage);
    }
}