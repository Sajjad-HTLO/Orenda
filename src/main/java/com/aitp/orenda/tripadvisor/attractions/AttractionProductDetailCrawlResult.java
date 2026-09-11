package com.aitp.orenda.tripadvisor.attractions;

/**
 * Outcome of crawling a single attraction product detail page (Stage 2).
 * Carries the parsed detail on success and a human-readable reason on failure.
 */
public record AttractionProductDetailCrawlResult(
        boolean successful,
        AttractionProductDetail detail,
        String errorMessage
) {

    public static AttractionProductDetailCrawlResult success(AttractionProductDetail detail) {
        return new AttractionProductDetailCrawlResult(true, detail, null);
    }

    public static AttractionProductDetailCrawlResult failure(String errorMessage) {
        return new AttractionProductDetailCrawlResult(false, null, errorMessage);
    }
}
