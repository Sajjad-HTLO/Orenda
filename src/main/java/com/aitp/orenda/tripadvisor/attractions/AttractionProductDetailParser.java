package com.aitp.orenda.tripadvisor.attractions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Parses an individual Tripadvisor attraction product page (Stage 2) and maps
 * the extracted fields onto the {@link AttractionProductDetail} model. Product
 * pages embed structured data as JSON-LD ({@code application/ld+json}) with a
 * {@code Product} type (name, image, offers, aggregateRating), which is the
 * most reliable source; DOM selectors are used as a fallback.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "tripadvisor.crawler.attraction-products.enabled", havingValue = "true")
public class AttractionProductDetailParser {

    private static final Pattern TRIPADVISOR_ID_PATTERN = Pattern.compile("-d(\\d+)-");
    private static final Pattern RATING_PATTERN = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern REVIEW_COUNT_PATTERN = Pattern.compile("([0-9,]+)");
    private static final Pattern DURATION_PATTERN = Pattern.compile(
            "([0-9]+(?:\\s*(?:to|[-–])\\s*[0-9]+(?:\\.[0-9]+)?)?\\s*(?:hours?|hrs?|days?|minutes?|mins?))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PRICE_PATTERN = Pattern.compile("(\\$|€|£|TL\\s?)\\s?([0-9]+(?:[.,][0-9]{2})?)");
    private static final Pattern COST_CONTEXT_PATTERN = Pattern.compile(
            "(?i)(admission|entry|entrance|ticket)\\s*(?:fee|price|cost)?[^0-9]{0,50}(\\$|€|£|₺|TL\\s?)?\\s?([0-9]+(?:[.,][0-9]{2})?)");

    private final ObjectMapper objectMapper = new ObjectMapper();

    public AttractionProductDetail parse(String html, String url, String sourceListingUrl) {
        Document document = Jsoup.parse(html, url);
        Long tripadvisorId = extractTripadvisorId(url);

        JsonNode ld = findProductJsonLd(document);
        String name = firstNonBlank(
                text(ld, "name"),
                selectText(document, "h1"));
        Double rating = doubleOrNull(text(ld, "aggregateRating", "ratingValue"));
        if (rating == null) {
            rating = extractRatingFromDom(document);
        }
        Integer reviewCount = intOrNull(text(ld, "aggregateRating", "reviewCount"));
        if (reviewCount == null) {
            reviewCount = extractReviewCountFromDom(document);
        }

        Double latitude = doubleOrNull(text(ld, "geo", "latitude"));
        Double longitude = doubleOrNull(text(ld, "geo", "longitude"));

        String price = firstNonBlank(
                offersPrice(ld),
                selectText(document, "[data-automation*='price']"),
                selectText(document, "[class*=price]"));
        String cost = firstNonBlank(
                offersPrice(ld),
                costFromBody(document));
        String duration = firstNonBlank(
                text(ld, "duration"),
                selectText(document, "[data-automation*='duration']"),
                selectText(document, "[class*=duration]"),
                durationFromBody(document));
        String cancellationPolicy = firstNonBlank(
                text(ld, "cancellationPolicy"),
                cancellationFromDom(document));
        String description = firstNonBlank(
                text(ld, "description"),
                selectText(document, "div[class*=description]"),
                selectText(document, "meta[name=description]", "content"));
        List<String> imageUrls = extractImageUrls(document, ld);

        AttractionProductDetail detail = AttractionProductDetail.builder()
                .tripadvisorId(tripadvisorId == null ? 0L : tripadvisorId)
                .url(url)
                .name(name)
                .latitude(latitude)
                .longitude(longitude)
                .rating(rating)
                .reviewCount(reviewCount)
                .price(price)
                .cost(cost)
                .duration(duration)
                .cancellationPolicy(cancellationPolicy)
                .description(description)
                .imageUrls(imageUrls)
                .sourceListingUrl(sourceListingUrl)
                .build();

        log.info("TRIPADVISOR_ATTRACTION_PRODUCT_DETAIL_PARSED url={} tripadvisorId={} name='{}' lat={} lon={} rating={} reviewCount={} price='{}' cost='{}' duration='{}' cancellation='{}' descriptionChars={} imageCount={}",
                url, detail.tripadvisorId(), detail.name(), detail.latitude(), detail.longitude(),
                detail.rating(), detail.reviewCount(), detail.price(), detail.cost(), detail.duration(),
                detail.cancellationPolicy(),
                detail.description() == null ? 0 : detail.description().length(),
                detail.imageUrls() == null ? 0 : detail.imageUrls().size());
        return detail;
    }

    // ==================== JSON-LD extraction ====================

    private JsonNode findProductJsonLd(Document document) {
        for (Element script : document.select("script[type=application/ld+json]")) {
            String raw = script.data();
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                JsonNode node = objectMapper.readTree(raw);
                JsonNode candidate = unwrapGraph(node);
                if (isProductNode(candidate)) {
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
                if (isProductNode(item)) {
                    return item;
                }
            }
            return null;
        }
        if (node.has("@graph") && node.get("@graph").isArray()) {
            for (JsonNode item : node.get("@graph")) {
                if (isProductNode(item)) {
                    return item;
                }
            }
        }
        return node;
    }

    private boolean isProductNode(JsonNode node) {
        if (node == null || !node.isObject()) {
            return false;
        }
        JsonNode type = node.get("@type");
        if (type == null) {
            return false;
        }
        String typeStr = type.isArray() ? type.toString() : type.asText();
        return typeStr.contains("Product") || typeStr.contains("TouristAttraction")
                || typeStr.contains("Event");
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

    private String offersPrice(JsonNode ld) {
        if (ld == null) {
            return null;
        }
        JsonNode offers = ld.get("offers");
        if (offers == null) {
            return null;
        }
        if (offers.isArray() && !offers.isEmpty()) {
            offers = offers.get(0);
        }
        if (offers == null || !offers.isObject()) {
            return null;
        }
        for (String key : List.of("lowPrice", "price")) {
            JsonNode value = offers.get(key);
            if (value != null && value.isNumber()) {
                String currency = text(offers, "priceCurrency");
                return (currency == null ? "$" : symbolFor(currency)) + value.asText();
            }
        }
        return null;
    }

    private String symbolFor(String currency) {
        return switch (currency == null ? "" : currency.toUpperCase()) {
            case "EUR" -> "€";
            case "GBP" -> "£";
            case "TRY", "TL" -> "₺";
            case "USD" -> "$";
            default -> currency + " ";
        };
    }

    /**
     * Best-effort extraction of a POI entry cost (admission / entry / ticket
     * fee) from the page body text, e.g. "Admission: 150 TL". Returns null when
     * no such phrasing with a price is present.
     */
    private String costFromBody(Document document) {
        String body = document.body() == null ? null : document.body().text();
        if (body == null || body.isBlank()) {
            return null;
        }
        Matcher matcher = COST_CONTEXT_PATTERN.matcher(body);
        if (matcher.find()) {
            String currency = matcher.group(2);
            String amount = matcher.group(3);
            return clean(currency == null || currency.isBlank() ? amount : currency + amount);
        }
        return null;
    }

    // ==================== DOM fallbacks ====================

    private String selectText(Document document, String selector) {
        return selectText(document, selector, null);
    }

    private String selectText(Document document, String selector, String attribute) {
        try {
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
        } catch (Exception e) {
            return null;
        }
    }

    private Double extractRatingFromDom(Document document) {
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
        Element reviews = document.selectFirst("span[class*=reviewCount], a[href*='#REVIEWS'] span");
        if (reviews != null) {
            Matcher matcher = REVIEW_COUNT_PATTERN.matcher(reviews.text());
            if (matcher.find()) {
                return parseInteger(matcher.group(1));
            }
        }
        return null;
    }

    private String durationFromBody(Document document) {
        String body = document.body() == null ? null : document.body().text();
        if (body == null || body.isBlank()) {
            return null;
        }
        Matcher matcher = DURATION_PATTERN.matcher(body);
        if (matcher.find()) {
            return clean(matcher.group(1));
        }
        return null;
    }

    private String cancellationFromDom(Document document) {
        for (Element element : document.select("[class*=cancellation], [data-automation*=cancellation]")) {
            String value = clean(element.text());
            if (value != null) {
                return value;
            }
        }
        String body = document.body() == null ? null : document.body().text();
        if (body != null && body.toLowerCase().contains("free cancellation")) {
            return "Free cancellation";
        }
        return null;
    }

    private List<String> extractImageUrls(Document document, JsonNode ld) {
        Set<String> urls = new LinkedHashSet<>();
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
        for (Element img : document.select("img[src*='dynamic-media-cdn.tripadvisor.com']")) {
            addImageUrl(urls, img.attr("src"));
        }
        for (Element img : document.select("img[src*='media.tacdn.com']")) {
            addImageUrl(urls, img.attr("src"));
        }
        for (Element img : document.select("img[data-lazyurl*='media.tacdn.com']")) {
            addImageUrl(urls, img.attr("data-lazyurl"));
        }
        return new ArrayList<>(urls);
    }

    private void addImageUrl(Set<String> urls, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String normalized = value.replace("\\u002F", "/");
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
