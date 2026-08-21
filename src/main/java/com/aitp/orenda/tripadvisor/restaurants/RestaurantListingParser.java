package com.aitp.orenda.tripadvisor.restaurants;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@ConditionalOnProperty(name = "tripadvisor.crawler.restaurants.enabled", havingValue = "true")
public class RestaurantListingParser {

    private static final String TRIPADVISOR_ORIGIN = "https://www.tripadvisor.com";

    public RestaurantListingParseResult parse(String html, String sourceListingUrl) {
        Document document = Jsoup.parse(html, sourceListingUrl);
        Map<String, RestaurantListing> restaurantsByUrl = new LinkedHashMap<>();
        int candidateLinks = 0;
        int rejectedWithoutId = 0;

        for (Element link : document.select("a[href*=/Restaurant_Review-]")) {
            candidateLinks++;
            String href = link.attr("href");
            String absoluteUrl = normalizeRestaurantUrl(href);
            if (absoluteUrl == null || absoluteUrl.isBlank()) {
                continue;
            }
            Long tripadvisorId = extractTripadvisorId(absoluteUrl);
            if (tripadvisorId == null) {
                rejectedWithoutId++;
                continue;
            }
            String name = extractName(link);
            RestaurantListing listing = RestaurantListing.builder()
                    .tripadvisorId(tripadvisorId)
                    .url(absoluteUrl)
                    .name(name)
                    .sourceListingUrl(sourceListingUrl)
                    .build();
            mergeRestaurant(restaurantsByUrl, absoluteUrl, listing);
        }

        RestaurantListingParseResult result = new RestaurantListingParseResult(
                restaurantsByUrl.values().stream().toList());
        log.info("Tripadvisor restaurant parser finished. sourceUrl={}, htmlLength={}, candidateRestaurantLinks={}, uniqueRestaurants={}, rejectedWithoutTripadvisorId={}",
                sourceListingUrl, html == null ? 0 : html.length(), candidateLinks, result.restaurantCount(), rejectedWithoutId);
        result.restaurants().stream().limit(5).forEach(restaurant ->
                log.info("Tripadvisor parsed restaurant sample. id={}, name={}, url={}",
                        restaurant.tripadvisorId(), restaurant.name(), restaurant.url()));
        if (result.restaurantCount() == 0) {
            log.warn("Tripadvisor restaurant parser extracted 0 restaurants from {}. title='{}', htmlLength={}",
                    sourceListingUrl, document.title(), html == null ? 0 : html.length());
        }
        return result;
    }

    /**
     * The same restaurant appears in multiple anchors on the page (photo
     * carousel, name link, review links, etc.). The photo-carousel anchor
     * usually comes first in the DOM but carries no name, so we only keep the
     * first entry when it already has a name and replace a name-less entry with
     * a later named one.
     */
    private void mergeRestaurant(Map<String, RestaurantListing> byUrl,
                                 String url, RestaurantListing candidate) {
        RestaurantListing existing = byUrl.get(url);
        if (existing == null || (isBlank(existing.name()) && !isBlank(candidate.name()))) {
            byUrl.put(url, candidate);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String normalizeRestaurantUrl(String href) {
        if (href == null || href.isBlank()) {
            return null;
        }
        String cleanHref = href.split("#", 2)[0].split("\\?", 2)[0];
        String absolute = cleanHref.startsWith("http") ? cleanHref : TRIPADVISOR_ORIGIN + cleanHref;
        URI uri = URI.create(absolute);
        return uri.getScheme() + "://" + uri.getHost() + uri.getPath();
    }

    private Long extractTripadvisorId(String url) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("-d(\\d+)-").matcher(url);
        if (!matcher.find()) {
            return null;
        }
        return Long.parseLong(matcher.group(1));
    }

    private static final java.util.Set<String> IGNORED_ARIA_LABELS = java.util.Set.of(
            "Previous Photo", "Next Photo", "Open carousel", "Close carousel",
            "Photo of ");

    private String extractName(Element link) {
        String ariaLabel = link.attr("aria-label");
        String name;
        if (ariaLabel != null && !ariaLabel.isBlank() && !isIgnoredAriaLabel(ariaLabel)) {
            name = ariaLabel.trim();
        } else {
            String text = link.text();
            name = text == null || text.isBlank() ? null : text.trim();
        }
        return cleanName(name);
    }

    private boolean isIgnoredAriaLabel(String ariaLabel) {
        String trimmed = ariaLabel.trim();
        return IGNORED_ARIA_LABELS.contains(trimmed)
                || trimmed.startsWith("Photo of ")
                || trimmed.startsWith("Review of ");
    }

    /**
     * The ranked restaurant list renders anchors like "3. 360 Panorama Rooftop
     * Restaurant" — strips the leading "N. " rank prefix so the stored name is
     * just the restaurant name.
     */
    private String cleanName(String name) {
        if (name == null) {
            return null;
        }
        return name.replaceFirst("^\\d+\\.\\s+", "").trim();
    }
}