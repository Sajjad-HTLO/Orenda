package com.sajad.AITP.tripadvisor.model;

/**
 * Outcome of crawling a single hotel detail page (Stage 2). Carries the parsed
 * detail on success and a human-readable reason on failure so the caller can
 * log exactly why a hotel failed instead of a bare boolean.
 */
public record HotelDetailCrawlResult(
        boolean successful,
        HotelDetail detail,
        String errorMessage
) {

    public static HotelDetailCrawlResult success(HotelDetail detail) {
        return new HotelDetailCrawlResult(true, detail, null);
    }

    public static HotelDetailCrawlResult failure(String errorMessage) {
        return new HotelDetailCrawlResult(false, null, errorMessage);
    }
}
