package com.sajad.AITP.tripadvisor.model;

public record ListingCrawlResult(
        int offset,
        String url,
        int hotelCount,
        boolean skipped,
        boolean successful,
        String errorMessage
) {

    public static ListingCrawlResult skipped(CrawlPage page, int hotelCount) {
        return new ListingCrawlResult(page.offset(), page.url(), hotelCount, true, true, null);
    }

    public static ListingCrawlResult success(CrawlPage page, int hotelCount) {
        return new ListingCrawlResult(page.offset(), page.url(), hotelCount, false, true, null);
    }

    public static ListingCrawlResult failed(CrawlPage page, Exception error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return new ListingCrawlResult(page.offset(), page.url(), 0, false, false, message);
    }
}
