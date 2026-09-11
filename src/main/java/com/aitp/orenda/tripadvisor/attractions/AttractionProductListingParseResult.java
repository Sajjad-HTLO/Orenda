package com.aitp.orenda.tripadvisor.attractions;

import java.util.List;

public record AttractionProductListingParseResult(
        List<AttractionProductListing> products
) {

    public int productCount() {
        return products.size();
    }
}
