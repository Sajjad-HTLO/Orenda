package com.aitp.orenda.tripadvisor.crawler;

import com.aitp.orenda.tripadvisor.config.TripadvisorCrawlerProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates the paginated listing URLs for every Tripadvisor listing type from
 * the single {@code tripadvisor.crawler.base-url}. Tripadvisor paginates these
 * listings by {@code -oa{offset}-}, where the offset increases by the page size
 * (e.g. 0 → 30 → 60 → 90) and is injected before the trailing city segment:
 * <pre>
 * .../Hotels-g293974-Istanbul-Hotels.html
 * .../Hotels-g293974-oa30-Istanbul-Hotels.html
 * .../Restaurants-g293974-Istanbul.html
 * .../Restaurants-g293974-oa30-Istanbul.html
 * .../Attractions-g293974-Activities-c26-t143-Istanbul.html
 * .../Attractions-g293974-Activities-c26-t143-oa30-Istanbul.html
 * </pre>
 * An offset of 0 yields the plain base URL without an {@code oa} segment. The
 * listing type is resolved in {@link TripadvisorListingType}.
 */
@Component
@ConditionalOnProperty(name = "tripadvisor.crawler.enabled", havingValue = "true")
public class TripadvisorPaginationGenerator {

    private static final Pattern OA_OFFSET_PATTERN = Pattern.compile("-oa(\\d+)-");
    private static final Pattern CITY_MARKER_PATTERN = Pattern.compile("(-[A-Z][A-Za-z]*)(?:-\\w+)?\\.html$");

    private final TripadvisorCrawlerProperties properties;

    public TripadvisorPaginationGenerator(TripadvisorCrawlerProperties properties) {
        this.properties = properties;
    }

    /**
     * Parses the pagination offset embedded in the configured base URL (e.g.
     * {@code -oa30-} yields 30). Returns 0 when there is no offset segment.
     */
    public int baseOffset() {
        return parseOffset(properties.baseUrl());
    }

    /**
     * Builds the listing page URL for the given offset from the configured base
     * URL.
     */
    public String pageUrlForOffset(int offset) {
        return pageUrlForOffset(offset, properties.baseUrl());
    }

    /**
     * Builds the listing page URL for the given offset from an explicit base
     * URL. Any existing {@code -oa{offset}-} segment is replaced; otherwise the
     * new offset is injected before the trailing city token. An offset of 0
     * produces the plain base URL.
     */
    public String pageUrlForOffset(int offset, String baseUrl) {
        if (offset <= 0) {
            return removeOffsetSegment(baseUrl);
        }
        Matcher existing = OA_OFFSET_PATTERN.matcher(baseUrl);
        if (existing.find()) {
            return existing.replaceFirst("-oa" + offset + "-");
        }
        Matcher city = CITY_MARKER_PATTERN.matcher(baseUrl);
        if (city.find()) {
            int insertAt = city.start(1);
            return baseUrl.substring(0, insertAt) + "-oa" + offset + baseUrl.substring(insertAt);
        }
        throw new IllegalArgumentException(
                "Tripadvisor base URL must be a /Hotels-, /Restaurants-, /Attractions- or /Attraction_Products- listing: " + baseUrl);
    }

    /**
     * Removes any existing {@code -oa{offset}-} segment, keeping a single dash
     * separator so the result stays well-formed.
     */
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