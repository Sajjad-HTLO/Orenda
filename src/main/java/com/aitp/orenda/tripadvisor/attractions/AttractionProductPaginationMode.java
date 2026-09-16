package com.aitp.orenda.tripadvisor.attractions;

/**
 * How the attraction products crawler discovers more POIs than the first
 * rendered batch:
 * <ul>
 *   <li>{@link #NEXT_PAGE_URL} (default) — generate the next listing page URL by
 *       advancing the Tripadvisor {@code -oa{offset}-} segment (e.g.
 *       {@code ...t143-Istanbul.html} → {@code ...t143-oa30-Istanbul.html}).</li>
 *   <li>{@link #SEE_MORE} — click the "See More" button on the single listing
 *       page until the full lazy-loaded list is revealed.</li>
 * </ul>
 */
public enum AttractionProductPaginationMode {
    NEXT_PAGE_URL,
    SEE_MORE;

    public static AttractionProductPaginationMode fromValue(String value) {
        if (value == null || value.isBlank()) {
            return NEXT_PAGE_URL;
        }
        try {
            return valueOf(value.trim().replace('-', '_').toUpperCase());
        } catch (IllegalArgumentException e) {
            return NEXT_PAGE_URL;
        }
    }
}
