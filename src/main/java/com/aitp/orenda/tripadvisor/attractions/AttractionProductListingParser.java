package com.aitp.orenda.tripadvisor.attractions;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts attraction product links ({@code AttractionProductReview-g...-d...})
 * from a rendered Tripadvisor {@code Attraction_Products} listing page.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "tripadvisor.crawler.attraction-products.enabled", havingValue = "true")
public class AttractionProductListingParser {

    private static final String TRIPADVISOR_ORIGIN = "https://www.tripadvisor.com";
    private static final Pattern TRIPADVISOR_ID_PATTERN = Pattern.compile("-d(\\d+)-");

    public AttractionProductListingParseResult parse(String html, String sourceListingUrl) {
        Document document = Jsoup.parse(html, sourceListingUrl);
        Map<String, AttractionProductListing> productsByUrl = new LinkedHashMap<>();
        int candidateLinks = 0;
        int rejectedWithoutId = 0;

        for (Element link : document.select("a[href*=/AttractionProductReview-]")) {
            candidateLinks++;
            String href = link.attr("href");
            String absoluteUrl = normalizeProductUrl(href);
            if (absoluteUrl == null || absoluteUrl.isBlank()) {
                continue;
            }
            Long tripadvisorId = extractTripadvisorId(absoluteUrl);
            if (tripadvisorId == null) {
                rejectedWithoutId++;
                continue;
            }
            String name = extractName(link);
            AttractionProductListing listing = AttractionProductListing.builder()
                    .tripadvisorId(tripadvisorId)
                    .url(absoluteUrl)
                    .name(name)
                    .sourceListingUrl(sourceListingUrl)
                    .build();
            mergeProduct(productsByUrl, absoluteUrl, listing);
        }

        List<AttractionProductListing> products = new ArrayList<>(productsByUrl.values());
        log.info("Tripadvisor attraction product parser finished. sourceUrl={}, htmlLength={}, candidateLinks={}, uniqueProducts={}, rejectedWithoutTripadvisorId={}",
                sourceListingUrl, html == null ? 0 : html.length(), candidateLinks, products.size(), rejectedWithoutId);
        products.stream().limit(5).forEach(product ->
                log.info("Tripadvisor parsed attraction product sample. id={}, name={}, url={}",
                        product.tripadvisorId(), product.name(), product.url()));
        if (products.isEmpty()) {
            log.warn("Tripadvisor attraction product parser extracted 0 products from {}. title='{}', htmlLength={}",
                    sourceListingUrl, document.title(), html == null ? 0 : html.length());
        }
        return new AttractionProductListingParseResult(products);
    }

    /**
     * The same product appears in multiple anchors on the page (photo carousel,
     * title link, review links). The first anchor usually carries no name, so a
     * name-less entry is replaced by a later named one.
     */
    private void mergeProduct(Map<String, AttractionProductListing> byUrl,
                              String url, AttractionProductListing candidate) {
        AttractionProductListing existing = byUrl.get(url);
        if (existing == null || (isBlank(existing.name()) && !isBlank(candidate.name()))) {
            byUrl.put(url, candidate);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String normalizeProductUrl(String href) {
        if (href == null || href.isBlank()) {
            return null;
        }
        String cleanHref = href.split("#", 2)[0].split("\\?", 2)[0];
        String absolute = cleanHref.startsWith("http") ? cleanHref : TRIPADVISOR_ORIGIN + cleanHref;
        try {
            URI uri = URI.create(absolute);
            return uri.getScheme() + "://" + uri.getHost() + uri.getPath();
        } catch (Exception e) {
            return null;
        }
    }

    private Long extractTripadvisorId(String url) {
        Matcher matcher = TRIPADVISOR_ID_PATTERN.matcher(url);
        if (!matcher.find()) {
            return null;
        }
        return Long.parseLong(matcher.group(1));
    }

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
        return trimmed.startsWith("Photo of ")
                || trimmed.startsWith("Review of ")
                || trimmed.startsWith("Previous")
                || trimmed.startsWith("Next");
    }

    private String cleanName(String name) {
        if (name == null) {
            return null;
        }
        return name.replaceFirst("^\\d+\\.\\s+", "").trim();
    }
}
