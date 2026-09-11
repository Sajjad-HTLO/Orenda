package com.aitp.orenda.tripadvisor.attractions;

import java.util.List;

public record AttractionProductCrawlResult(
        String url,
        int productCount,
        boolean successful,
        String errorMessage,
        List<AttractionProductListing> products,
        String categoryName
) {

    public static AttractionProductCrawlResult success(String url, List<AttractionProductListing> products, String categoryName) {
        return new AttractionProductCrawlResult(url, products.size(), true, null, products, categoryName);
    }

    public static AttractionProductCrawlResult failed(String url, Exception error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return new AttractionProductCrawlResult(url, 0, false, message, List.of(), null);
    }
}
