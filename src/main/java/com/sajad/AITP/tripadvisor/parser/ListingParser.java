package com.sajad.AITP.tripadvisor.parser;

import com.sajad.AITP.tripadvisor.model.HotelListing;
import com.sajad.AITP.tripadvisor.model.ListingParseResult;
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
@ConditionalOnProperty(name = "tripadvisor.crawler.enabled", havingValue = "true")
public class ListingParser {

    private static final String TRIPADVISOR_ORIGIN = "https://www.tripadvisor.com";

    public ListingParseResult parse(String html, String sourceListingUrl) {
        Document document = Jsoup.parse(html, sourceListingUrl);
        Map<String, HotelListing> hotelsByUrl = new LinkedHashMap<>();
        int candidateLinks = 0;
        int rejectedWithoutId = 0;

        for (Element link : document.select("a[href*=/Hotel_Review-]")) {
            candidateLinks++;
            String href = link.attr("href");
            String absoluteUrl = normalizeHotelUrl(href);
            if (absoluteUrl == null || absoluteUrl.isBlank()) {
                continue;
            }
            Long tripadvisorId = extractTripadvisorId(absoluteUrl);
            if (tripadvisorId == null) {
                rejectedWithoutId++;
                continue;
            }
            String name = extractName(link);
            hotelsByUrl.putIfAbsent(absoluteUrl, HotelListing.builder()
                    .tripadvisorId(tripadvisorId)
                    .url(absoluteUrl)
                    .name(name)
                    .sourceListingUrl(sourceListingUrl)
                    .build());
        }

        ListingParseResult result = new ListingParseResult(hotelsByUrl.values().stream().toList());
        log.info("Tripadvisor listing parser finished. sourceUrl={}, htmlLength={}, candidateHotelLinks={}, uniqueHotels={}, rejectedWithoutTripadvisorId={}",
                sourceListingUrl, html == null ? 0 : html.length(), candidateLinks, result.hotelCount(), rejectedWithoutId);
        result.hotels().stream().limit(5).forEach(hotel ->
                log.info("Tripadvisor parsed hotel sample. id={}, name={}, url={}", hotel.tripadvisorId(), hotel.name(), hotel.url()));
        if (result.hotelCount() == 0) {
            log.warn("Tripadvisor parser extracted 0 hotels from {}. title='{}', htmlLength={}",
                    sourceListingUrl, document.title(), html == null ? 0 : html.length());
        }
        return result;
    }

    private String normalizeHotelUrl(String href) {
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

    private String extractName(Element link) {
        String ariaLabel = link.attr("aria-label");
        if (ariaLabel != null && !ariaLabel.isBlank()) {
            return ariaLabel.trim();
        }
        String text = link.text();
        return text == null || text.isBlank() ? null : text.trim();
    }
}
