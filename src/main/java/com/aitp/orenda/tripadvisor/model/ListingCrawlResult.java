package com.aitp.orenda.tripadvisor.model;

import java.util.List;

public record ListingCrawlResult(
        int offset,
        String url,
        int hotelCount,
        boolean skipped,
        boolean successful,
        String errorMessage,
        List<HotelListing> hotels
) {

    public static ListingCrawlResult skipped(CrawlPage page, int hotelCount) {
        return new ListingCrawlResult(page.offset(), page.url(), hotelCount, true, true, null, List.of());
    }

    public static ListingCrawlResult success(CrawlPage page, int hotelCount, List<HotelListing> hotels) {
        return new ListingCrawlResult(page.offset(), page.url(), hotelCount, false, true, null, hotels);
    }

    public static ListingCrawlResult failed(CrawlPage page, Exception error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return new ListingCrawlResult(page.offset(), page.url(), 0, false, false, message, List.of());
    }
}
