package com.aitp.orenda.tripadvisor.crawler;

/**
 * The kind of Tripadvisor listing being crawled, derived from the single
 * configured {@code tripadvisor.crawler.base-url}. It determines which anchor
 * type is treated as a POI on the listing page and which pipeline crawls the
 * run:
 * <ul>
 *   <li>{@link #HOTEL} — {@code /Hotels-...} listings whose items are hotels
 *       ({@code Hotel_Review-} links), stored as
 *       {@code accommodation}/{@code hotel}.</li>
 *   <li>{@link #RESTAURANT} — {@code /Restaurants-...} listings whose items are
 *       restaurants ({@code Restaurant_Review-} links), stored as
 *       {@code food_drink}/{@code restaurant}.</li>
 *   <li>{@link #TOUR_PRODUCT} — {@code /Attraction_Products-...} listings whose
 *       items are bookable tours ({@code AttractionProductReview-} links),
 *       stored as {@code activity}.</li>
 *   <li>{@link #ATTRACTION} — {@code /Attractions-...-Activities-...} category
 *       listings whose items are attractions such as museums or malls
 *       ({@code Attraction_Review-} links), stored as {@code attraction}.</li>
 * </ul>
 */
public enum TripadvisorListingType {
    HOTEL("Hotel_Review-", "accommodation"),
    RESTAURANT("Restaurant_Review-", "food_drink"),
    TOUR_PRODUCT("AttractionProductReview-", "activity"),
    ATTRACTION("Attraction_Review-", "attraction");

    private final String linkMarker;
    private final String poiCategory;

    TripadvisorListingType(String linkMarker, String poiCategory) {
        this.linkMarker = linkMarker;
        this.poiCategory = poiCategory;
    }

    /**
     * The substring identifying a POI anchor href on the listing page, e.g.
     * {@code Hotel_Review-}, {@code Restaurant_Review-},
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
     * Detects the listing type from the configured base URL:
     * <ul>
     *   <li>{@code Attraction_Products} → {@link #TOUR_PRODUCT}</li>
     *   <li>{@code /Attractions-} → {@link #ATTRACTION}</li>
     *   <li>{@code /Restaurants-} → {@link #RESTAURANT}</li>
     *   <li>{@code /Hotels-} → {@link #HOTEL}</li>
     * </ul>
     * Anything else defaults to {@link #HOTEL}, the crawler's original base case.
     */
    public static TripadvisorListingType detect(String listingUrl) {
        if (listingUrl == null || listingUrl.isBlank()) {
            return HOTEL;
        }
        if (listingUrl.contains("Attraction_Products")) {
            return TOUR_PRODUCT;
        }
        if (listingUrl.contains("/Attractions-")) {
            return ATTRACTION;
        }
        if (listingUrl.contains("/Restaurants-")) {
            return RESTAURANT;
        }
        if (listingUrl.contains("/Hotels-")) {
            return HOTEL;
        }
        return HOTEL;
    }
}