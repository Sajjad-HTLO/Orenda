package com.sajad.AITP.tripadvisor.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sajad.AITP.tripadvisor.model.HotelDetail;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses an individual Tripadvisor hotel review page (Stage 2) and maps the
 * extracted fields onto the {@link HotelDetail} model.
 * <p>
 * Tripadvisor embeds structured data as JSON-LD ({@code application/ld+json})
 * blocks. These are the most reliable source for name, address, geo
 * coordinates, aggregate rating, review count, price range and telephone.
 * DOM selectors are used as a fallback for fields not present in JSON-LD.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "tripadvisor.crawler.enabled", havingValue = "true")
public class HotelDetailParser {

    private static final Pattern TRIPADVISOR_ID_PATTERN = Pattern.compile("-d(\\d+)-");
    private static final Pattern RATING_PATTERN = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern REVIEW_COUNT_PATTERN = Pattern.compile("([0-9,]+)");

    private final ObjectMapper objectMapper = new ObjectMapper();

    public HotelDetail parse(String html, String url, String sourceListingUrl) {
        Document document = Jsoup.parse(html, url);
        Long tripadvisorId = extractTripadvisorId(url);

        JsonNode ld = findHotelJsonLd(document);
        String name = firstNonBlank(
                text(ld, "name"),
                selectText(document, "h1#HEADING"),
                selectText(document, "h1"));
        String address = firstNonBlank(
                text(ld, "address", "streetAddress"),
                selectText(document, "span[class*=streetAddress]"),
                selectText(document, "[data-test-target=address]"));
        String locality = firstNonBlank(
                text(ld, "address", "addressLocality"),
                selectText(document, "span[class*=locality]"));
        String country = firstNonBlank(
                countryText(ld),
                text(ld, "address", "addressCountry"),
                selectText(document, "span[class*=country-name]"));
        String postalCode = firstNonBlank(
                text(ld, "address", "postalCode"),
                selectText(document, "span[class*=postal-code]"));

        Double latitude = doubleOrNull(text(ld, "geo", "latitude"));
        Double longitude = doubleOrNull(text(ld, "geo", "longitude"));
        if (latitude == null || longitude == null) {
            Double[] geo = extractGeoFromMap(document);
            if (geo != null) {
                latitude = geo[0];
                longitude = geo[1];
            }
        }

        Double rating = doubleOrNull(text(ld, "aggregateRating", "ratingValue"));
        if (rating == null) {
            rating = extractRatingFromDom(document);
        }
        Integer reviewCount = intOrNull(text(ld, "aggregateRating", "reviewCount"));
        if (reviewCount == null) {
            reviewCount = extractReviewCountFromDom(document);
        }

        String priceRange = firstNonBlank(
                text(ld, "priceRange"),
                selectText(document, "span[class*=priceRange]"));
        String starRating = firstNonBlank(
                text(ld, "starRating"),
                extractStarRatingFromDom(document));
        String phone = firstNonBlank(
                text(ld, "telephone"),
                extractPhoneFromDom(document));
        String description = firstNonBlank(
                text(ld, "description"),
                selectText(document, "div[class*=description]"),
                selectText(document, "meta[name=description]", "content"));
        List<String> imageUrls = extractImageUrls(document, ld);

        HotelDetail detail = HotelDetail.builder()
                .tripadvisorId(tripadvisorId == null ? 0L : tripadvisorId)
                .url(url)
                .name(name)
                .address(address)
                .locality(locality)
                .country(country)
                .postalCode(postalCode)
                .latitude(latitude)
                .longitude(longitude)
                .rating(rating)
                .reviewCount(reviewCount)
                .priceRange(priceRange)
                .starRating(starRating)
                .phone(phone)
                .description(description)
                .imageUrls(imageUrls)
                .sourceListingUrl(sourceListingUrl)
                .build();

        log.info("TRIPADVISOR_HOTEL_DETAIL_PARSED url={} tripadvisorId={} name='{}' address='{}' locality='{}' country='{}' postalCode='{}' lat={} lon={} rating={} reviewCount={} priceRange='{}' starRating='{}' phone='{}' descriptionChars={} imageCount={}",
                url, detail.tripadvisorId(), detail.name(), detail.address(), detail.locality(), detail.country(),
                detail.postalCode(), detail.latitude(), detail.longitude(), detail.rating(), detail.reviewCount(),
                detail.priceRange(), detail.starRating(), detail.phone(),
                detail.description() == null ? 0 : detail.description().length(),
                detail.imageUrls() == null ? 0 : detail.imageUrls().size());
        return detail;
    }

    // ==================== JSON-LD extraction ====================

    /**
     * Finds the first JSON-LD block describing a Hotel/LodgingBusiness and
     * returns its root node, or {@code null} if none is present.
     */
    private JsonNode findHotelJsonLd(Document document) {
        for (Element script : document.select("script[type=application/ld+json]")) {
            String raw = script.data();
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                JsonNode node = objectMapper.readTree(raw);
                JsonNode candidate = unwrapGraph(node);
                if (isHotelNode(candidate)) {
                    return candidate;
                }
            } catch (Exception e) {
                log.debug("Skipping unparseable JSON-LD block: {}", e.getMessage());
            }
        }
        return null;
    }

    private JsonNode unwrapGraph(JsonNode node) {
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (isHotelNode(item)) {
                    return item;
                }
            }
            return null;
        }
        if (node.has("@graph") && node.get("@graph").isArray()) {
            for (JsonNode item : node.get("@graph")) {
                if (isHotelNode(item)) {
                    return item;
                }
            }
        }
        return node;
    }

    private boolean isHotelNode(JsonNode node) {
        if (node == null || !node.isObject()) {
            return false;
        }
        JsonNode type = node.get("@type");
        if (type == null) {
            return false;
        }
        String typeStr = type.isArray() ? type.toString() : type.asText();
        return typeStr.contains("Hotel") || typeStr.contains("LodgingBusiness");
    }

    private String text(JsonNode node, String... path) {
        if (node == null) {
            return null;
        }
        JsonNode current = node;
        for (String key : path) {
            if (current == null || !current.isObject()) {
                return null;
            }
            current = current.get(key);
        }
        if (current == null) {
            return null;
        }
        if (current.isTextual()) {
            return clean(current.asText());
        }
        if (current.isNumber()) {
            return current.asText();
        }
        if (current.isObject() && current.has("@value")) {
            return clean(current.get("@value").asText());
        }
        return null;
    }

    // ==================== DOM fallbacks ====================

    private String selectText(Document document, String selector) {
        return selectText(document, selector, null);
    }

    private String selectText(Document document, String selector, String attribute) {
        Element element = document.selectFirst(selector);
        if (element == null) {
            return null;
        }
        String text = null;
        if (attribute != null && !attribute.isBlank()) {
            text = element.attr(attribute);
        }
        if (text == null || text.isBlank()) {
            text = element.text();
        }
        return clean(text);
    }

    private Double extractRatingFromDom(Document document) {
        // Tripadvisor renders the rating as an SVG with aria-label like "4.5 of 5 bubbles"
        Element bubble = document.selectFirst("[aria-label*='of 5 bubbles']");
        if (bubble != null) {
            Matcher matcher = RATING_PATTERN.matcher(bubble.attr("aria-label"));
            if (matcher.find()) {
                return parseDouble(matcher.group(1));
            }
        }
        return null;
    }

    private Integer extractReviewCountFromDom(Document document) {
        Element reviews = document.selectFirst("span[class*=reviewCount], a[href*='-Reviews-'] span");
        if (reviews != null) {
            Matcher matcher = REVIEW_COUNT_PATTERN.matcher(reviews.text());
            if (matcher.find()) {
                return parseInteger(matcher.group(1));
            }
        }
        return null;
    }

    /**
     * Tripadvisor embeds the map marker coordinates in a JS variable
     * ({@code window.__WEB_ROOT__} or a {@code data-lat}/{@code data-lng}
     * attribute). Attempts a few known patterns as a last-resort fallback.
     */
    private Double[] extractGeoFromMap(Document document) {
        Element map = document.selectFirst("[data-lat][data-lng]");
        if (map != null) {
            Double lat = parseDouble(map.attr("data-lat"));
            Double lng = parseDouble(map.attr("data-lng"));
            if (lat != null && lng != null) {
                return new Double[]{lat, lng};
            }
        }
        return null;
    }

    // ==================== Country / star / phone / images ====================

    /**
     * Reads the country from the JSON-LD address. Tripadvisor represents
     * {@code addressCountry} as an object like
     * {@code {"@type":"Country","name":"TR"}}, so we must read the nested
     * {@code name} field rather than treating it as a plain string.
     */
    private String countryText(JsonNode ld) {
        if (ld == null) {
            return null;
        }
        JsonNode address = ld.get("address");
        if (address == null || !address.isObject()) {
            return null;
        }
        JsonNode country = address.get("addressCountry");
        if (country == null) {
            return null;
        }
        if (country.isTextual()) {
            return clean(country.asText());
        }
        if (country.isObject()) {
            JsonNode name = country.get("name");
            if (name != null && name.isTextual()) {
                return clean(name.asText());
            }
        }
        return null;
    }

    /**
     * Best-effort star rating extraction from the DOM. Tripadvisor renders the
     * star rating as an SVG with an aria-label like "4 of 5 stars" or a text
     * like "4-star hotel". Returns null when not present.
     */
    private String extractStarRatingFromDom(Document document) {
        Element star = document.selectFirst("[aria-label*='star'], [aria-label*='Star']");
        if (star != null) {
            String label = star.attr("aria-label");
            Matcher matcher = Pattern.compile("(\\d+)\\s*of\\s*5\\s*stars?").matcher(label);
            if (matcher.find()) {
                return matcher.group(1) + " star";
            }
        }
        Element text = document.selectFirst("span[class*=starRating], div[class*=starRating]");
        if (text != null) {
            String t = text.text();
            Matcher matcher = Pattern.compile("(\\d+)\\s*star").matcher(t);
            if (matcher.find()) {
                return matcher.group(1) + " star";
            }
        }
        return null;
    }

    /**
     * Best-effort phone extraction from the DOM. Tripadvisor links the phone
     * via an anchor with {@code href="tel:..."}. Returns null when not present.
     */
    private String extractPhoneFromDom(Document document) {
        Element tel = document.selectFirst("a[href^='tel:']");
        if (tel != null) {
            String href = tel.attr("href");
            if (href != null && href.startsWith("tel:")) {
                return clean(href.substring(4));
            }
        }
        return null;
    }

    /**
     * Extracts image URLs from the JSON-LD {@code image} field and from the
     * rendered DOM {@code <img>} tags. Deduplicates and returns a stable list.
     */
    private List<String> extractImageUrls(Document document, JsonNode ld) {
        Set<String> urls = new LinkedHashSet<>();
        // JSON-LD image (single URL or array)
        if (ld != null) {
            JsonNode image = ld.get("image");
            if (image != null) {
                if (image.isTextual()) {
                    addImageUrl(urls, image.asText());
                } else if (image.isArray()) {
                    for (JsonNode item : image) {
                        if (item.isTextual()) {
                            addImageUrl(urls, item.asText());
                        }
                    }
                }
            }
        }
        // DOM <img> tags pointing at Tripadvisor's CDN
        for (Element img : document.select("img[src*='dynamic-media-cdn.tripadvisor.com']")) {
            String src = img.attr("src");
            if (src != null && !src.isBlank()) {
                addImageUrl(urls, src);
            }
        }
        return new ArrayList<>(urls);
    }

    private void addImageUrl(Set<String> urls, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        // Normalize unicode-escaped URLs (e.g. \u002F -> /)
        String normalized = value.replace("\\u002F", "/");
        // Strip query string for a clean canonical URL
        int q = normalized.indexOf('?');
        if (q >= 0) {
            normalized = normalized.substring(0, q);
        }
        if (!normalized.isBlank()) {
            urls.add(normalized);
        }
    }

    // ==================== Helpers ====================

    private Long extractTripadvisorId(String url) {
        if (url == null) {
            return null;
        }
        Matcher matcher = TRIPADVISOR_ID_PATTERN.matcher(url);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private Double parseDouble(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(value.replace(",", "."));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer parseInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.replace(",", "").replace(".", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double doubleOrNull(String value) {
        return parseDouble(value);
    }

    private Integer intOrNull(String value) {
        return parseInteger(value);
    }
}
