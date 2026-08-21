package com.aitp.orenda.tripadvisor.restaurants;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates paginated restaurant listing URLs from the base URL. Tripadvisor
 * restaurant listings paginate by {@code -oa{offset}-} where offset increases by
 * the page size (e.g. 0 → 30 → 60 → 90). An offset of 0 yields the plain base
 * URL without an {@code oa} segment.
 */
@Component
@ConditionalOnProperty(name = "tripadvisor.crawler.restaurants.enabled", havingValue = "true")
public class RestaurantPaginationGenerator {

    private static final Pattern OA_OFFSET_PATTERN = Pattern.compile("-oa(\\d+)-");

    private final RestaurantCrawlerProperties properties;

    public RestaurantPaginationGenerator(RestaurantCrawlerProperties properties) {
        this.properties = properties;
    }

    /**
     * Parses the pagination offset embedded in the base URL (e.g. {@code -oa30-}
     * yields 30). Returns 0 when the base URL has no offset segment.
     */
    public int baseOffset() {
        return parseOffset(properties.baseUrl());
    }

    /**
     * Builds the listing page URL for the given offset. Any existing
     * {@code -oa{offset}-} segment is first stripped, then the new offset is
     * injected before the city marker. An offset of 0 produces the plain base
     * URL.
     */
    public String pageUrlForOffset(int offset) {
        String baseUrl = properties.baseUrl();
        if (offset <= 0) {
            return removeOffsetSegment(baseUrl);
        }
        Matcher matcher = OA_OFFSET_PATTERN.matcher(baseUrl);
        if (matcher.find()) {
            return matcher.replaceFirst("-oa" + offset + "-");
        }
        int cityMarker = baseUrl.indexOf("-Istanbul");
        if (cityMarker < 0) {
            throw new IllegalArgumentException(
                    "Tripadvisor Istanbul restaurant base URL must contain -Istanbul: " + baseUrl);
        }
        return baseUrl.substring(0, cityMarker) + "-oa" + offset + baseUrl.substring(cityMarker);
    }

    private String removeOffsetSegment(String url) {
        if (url == null) {
            return url;
        }
        return OA_OFFSET_PATTERN.matcher(url).replaceFirst("-");
    }

    public int nextOffset(int offset) {
        return offset + properties.pageSize();
    }

    private int parseOffset(String url) {
        if (url == null) {
            return 0;
        }
        Matcher matcher = OA_OFFSET_PATTERN.matcher(url);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }
}