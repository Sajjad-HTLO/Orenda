package com.aitp.orenda.tripadvisor.crawler;

import com.aitp.orenda.tripadvisor.config.TripadvisorCrawlerProperties;
import com.aitp.orenda.tripadvisor.model.CrawlPage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@ConditionalOnProperty(name = "tripadvisor.crawler.enabled", havingValue = "true")
public class PaginationGenerator {

    private static final Pattern OA_OFFSET_PATTERN = Pattern.compile("-oa(\\d+)-");

    private final TripadvisorCrawlerProperties properties;

    public PaginationGenerator(TripadvisorCrawlerProperties properties) {
        this.properties = properties;
    }

    /**
     * Parses the pagination offset embedded in the base URL (e.g. {@code -oa90-}
     * yields 90). Returns 0 when the base URL has no offset segment.
     */
    public int baseOffset() {
        return parseOffset(properties.baseUrl());
    }

    /**
     * Builds the listing page URL for the given offset. Any existing
     * {@code -oa{offset}-} segment is first stripped from the base URL, then the
     * new offset is injected as {@code -oa{offset}-} at the city marker. This
     * prevents a double offset (e.g. {@code -oa90-oa90-}) when the base URL
     * already carries an {@code oa} segment. An offset of 0 produces the plain
     * base URL without an {@code oa} segment.
     */
    public CrawlPage pageForOffset(int offset) {
        String baseUrl = properties.baseUrl();
        int cityMarker = baseUrl.indexOf("-Istanbul-");
        if (cityMarker < 0) {
            throw new IllegalArgumentException("Tripadvisor Istanbul hotel base URL must contain -Istanbul-: " + baseUrl);
        }
        if (offset <= 0) {
            return CrawlPage.builder()
                    .offset(0)
                    .url(removeOffsetSegment(baseUrl))
                    .build();
        }
        // Replace any existing -oa{offset}- segment in place (e.g. -oa90- -> -oa120-)
        // so we never produce a double offset like -oa90-oa90-.
        String url = replaceOffsetSegment(baseUrl, offset);
        return CrawlPage.builder()
                .offset(offset)
                .url(url)
                .build();
    }

    /**
     * Replaces the digits of an existing {@code -oa{offset}-} segment with the
     * given offset, preserving the surrounding dashes. If the URL has no offset
     * segment, one is injected before the city marker.
     */
    private String replaceOffsetSegment(String url, int offset) {
        Matcher matcher = OA_OFFSET_PATTERN.matcher(url);
        if (matcher.find()) {
            return matcher.replaceFirst("-oa" + offset + "-");
        }
        int cityMarker = url.indexOf("-Istanbul-");
        return url.substring(0, cityMarker) + "-oa" + offset + url.substring(cityMarker);
    }

    /**
     * Removes any existing {@code -oa{offset}-} segment from the given URL,
     * keeping a single dash separator so the result stays well-formed.
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
