package com.aitp.orenda.tripadvisor.attractions;

/**
 * The kind of Tripadvisor listing being crawled, derived from the listing URL.
 * It determines which anchor type is treated as a POI and which {@code poi}
 * category the crawled rows are stored under:
 * <ul>
 *   <li>{@link #TOUR_PRODUCT} — {@code /Attraction_Products-...} listings whose
 *       items are bookable tours ({@code AttractionProductReview-} links),
 *       stored as {@code activity}.</li>
 *   <li>{@link #ATTRACTION} — {@code /Attractions-...-Activities-...} category
 *       listings whose items are attractions such as museums or malls
 *       ({@code Attraction_Review-} links), stored as {@code attraction}.</li>
 *   <li>{@link #RESTAURANT} — {@code /Restaurants-...} listings whose items are
 *       restaurants ({@code Restaurant_Review-} links), stored as
 *       {@code restaurant}.</li>
 * </ul>
 */
public enum AttractionListingType {
    TOUR_PRODUCT("AttractionProductReview-", "activity"),
    ATTRACTION("Attraction_Review-", "attraction"),
    RESTAURANT("Restaurant_Review-", "restaurant");

    private final String linkMarker;
    private final String poiCategory;

    AttractionListingType(String linkMarker, String poiCategory) {
        this.linkMarker = linkMarker;
        this.poiCategory = poiCategory;
    }

    /**
     * The substring identifying a POI anchor href on the listing page, e.g.
     * {@code AttractionProductReview-} or {@code Attraction_Review-}.
     */
    public String linkMarker() {
        return linkMarker;
    }

    /**
     * The {@code poi.category} value used when persisting this listing's POIs.
     */
    public String poiCategory() {
        return poiCategory;
    }

    /**
     * Detects the listing type from a listing URL:
     * <ul>
     *   <li>{@code Attraction_Products} → {@link #TOUR_PRODUCT}</li>
     *   <li>{@code /Attractions-} → {@link #ATTRACTION}</li>
     *   <li>{@code /Restaurants-} or {@code Restaurant_Review-} → {@link #RESTAURANT}</li>
     * </ul>
     * Anything else defaults to {@link #TOUR_PRODUCT} to preserve the original
     * behaviour.
     */
    public static AttractionListingType detect(String listingUrl) {
        if (listingUrl == null || listingUrl.isBlank()) {
            return TOUR_PRODUCT;
        }
        if (listingUrl.contains("Attraction_Products")) {
            return TOUR_PRODUCT;
        }
        if (listingUrl.contains("/Attractions-")) {
            return ATTRACTION;
        }
        if (listingUrl.contains("/Restaurants-") || listingUrl.contains("Restaurant_Review-")) {
            return RESTAURANT;
        }
        return TOUR_PRODUCT;
    }
}
