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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts individual traveler reviews from a rendered Tripadvisor attraction
 * product page. The page renders a carousel of recent review cards in the DOM
 * (each carrying a bubble rating, reviewer name, date and comment), while a
 * JSON-LD block carries the review title/date/author/body. This parser reads
 * the DOM review cards (the reliable source for the per-review bubble rating)
 * and enriches each with its title from the JSON-LD where a reviewer/date match
 * can be made. Per-aspect sub-ratings are captured when present (attraction
 * products generally expose none).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "tripadvisor.crawler.attraction-products.enabled", havingValue = "true")
public class AttractionProductReviewParser {

    private static final Pattern TRIPADVISOR_ID_PATTERN = Pattern.compile("-d(\\d+)-");
    private static final Pattern BUBBLE_RATING_PATTERN = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*of\\s*5\\s*bubbles");
    private static final Pattern DATE_PATTERN = Pattern.compile("(?i)(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\\s+(\\d{4})");
    private static final Map<String, Integer> MONTHS = new HashMap<>() {{
        put("jan", 1); put("feb", 2); put("mar", 3); put("apr", 4); put("may", 5);
        put("jun", 6); put("jul", 7); put("aug", 8); put("sep", 9); put("oct", 10);
        put("nov", 11); put("dec", 12);
    }};

    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<CrawledPoiReview> parse(String html, String sourceUrl, int maxReviews) {
        Document document = Jsoup.parse(html, sourceUrl);
        Map<String, String> jsonLdTitles = parseJsonLdTitles(document);
        long tripadvisorId = tripadvisorId(sourceUrl);

        List<CrawledPoiReview> reviews = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Element bubble : document.select("[data-automation='bubbleRatingImage'] title")) {
            if (reviews.size() >= maxReviews) {
                break;
            }
            String bubbleText = bubble.text();
            Matcher ratingMatcher = BUBBLE_RATING_PATTERN.matcher(bubbleText);
            if (!ratingMatcher.find()) {
                continue;
            }
            Element card = bubble.closest("li");
            if (card == null) {
                continue;
            }
            Integer rating = Math.round((float) Double.parseDouble(ratingMatcher.group(1)));

            String reviewer;
            String date;
            String title;
            String comment;
            Element reviewerLink = card.selectFirst("a[class*='BMQDV']");
            if (reviewerLink != null) {
                // New-format card: reviewer is a profile link, has a date, no title in DOM.
                reviewer = reviewerLink.text().trim();
                date = textOf(card.selectFirst("div.biGQs._P.VImYz.ZNjnF"));
                title = jsonLdTitles.get(reviewerKey(reviewer, date));
                comment = textOf(card.selectFirst("div.biGQs._P.AWdfh"));
            } else {
                // Older-format card: reviewer is a plain name div, has a title but no date.
                reviewer = textOf(card.selectFirst("div.biGQs._P.VImYz.ZNjnF"));
                date = null;
                title = textOf(card.selectFirst("div[class*='SewaP']"));
                comment = textOf(card.selectFirst("div.biGQs._P.VImYz.AWdfh"));
            }
            if (reviewer == null) {
                continue;
            }

            String dedupeKey = reviewer + "|" + (date == null ? "" : date) + "|" + (comment == null ? "" : comment.length());
            if (!seen.add(dedupeKey)) {
                continue;
            }

            reviews.add(CrawledPoiReview.builder()
                    .tripadvisorId(tripadvisorId)
                    .reviewerName(reviewer)
                    .rating(rating)
                    .title(title)
                    .comment(comment)
                    .reviewDate(date)
                    .aspects(extractAspects(card))
                    .sourceUrl(sourceUrl)
                    .build());
        }

        log.info("Tripadvisor attraction product reviews parsed. url={}, extractedReviews={}, maxReviews={}",
                sourceUrl, reviews.size(), maxReviews);
        return reviews;
    }

    /**
     * Reads the {@code review} array from the product JSON-LD and indexes each
     * review's title by reviewer + year-month so DOM review cards can be
     * enriched with a title (the DOM cards don't render the title).
     */
    private Map<String, String> parseJsonLdTitles(Document document) {
        Map<String, String> titles = new HashMap<>();
        Element script = document.selectFirst("[data-automation='poi-jsonld'] script[type='application/ld+json']");
        if (script == null) {
            return titles;
        }
        try {
            JsonNode node = objectMapper.readTree(script.data());
            JsonNode reviews = node.get("review");
            if (reviews == null || !reviews.isArray()) {
                return titles;
            }
            for (JsonNode review : reviews) {
                JsonNode author = review.get("author");
                JsonNode name = review.get("name");
                JsonNode date = review.get("datePublished");
                if (author == null || name == null || date == null) {
                    continue;
                }
                String authorName = author.path("name").asText(null);
                String title = name.asText(null);
                String key = reviewerKey(authorName, date.asText(null));
                if (title != null && key != null) {
                    titles.putIfAbsent(key, title);
                }
            }
        } catch (Exception e) {
            log.debug("Could not parse attraction product JSON-LD review titles: {}", e.getMessage());
        }
        return titles;
    }

    /**
     * Builds a normalised key (reviewer + year + month) that matches a DOM date
     * like {@code "Sep 2026"} with a JSON-LD date like {@code "2026-09-08"}.
     */
    private String reviewerKey(String reviewer, String date) {
        if (reviewer == null || reviewer.isBlank()) {
            return null;
        }
        Integer year = null;
        Integer month = null;
        if (date != null) {
            Matcher m = DATE_PATTERN.matcher(date);
            if (m.find()) {
                month = MONTHS.get(m.group(1).toLowerCase());
                year = parseInt(m.group(2));
            } else if (date.matches("\\d{4}-\\d{2}-.*")) {
                year = parseInt(date.substring(0, 4));
                month = parseInt(date.substring(5, 7));
            }
        }
        return reviewer.toLowerCase().trim() + "|" + (year == null ? "" : year) + "|" + (month == null ? "" : month);
    }

    /**
     * Best-effort aspect extraction. Attraction/tour pages expose only an
     * overall bubble rating (no aspect breakdown), so this returns an empty map
     * for them. When a POI type does render aspect rows (e.g. hotel Cleanliness,
     * restaurant Food/Service/Value), the page structure is captured here.
     */
    private Map<String, Integer> extractAspects(Element card) {
        Map<String, Integer> aspects = new LinkedHashMap<>();
        for (Element row : card.select("[class*=rating]")) {
            String text = row.text();
            if (text == null || text.isBlank()) {
                continue;
            }
            Matcher m = Pattern.compile("(?i)(cleanliness|service|value|location|food|atmosphere|accessibility|cost)\\s*[\\u2022:]?\\s*([0-9]+(?:\\.[0-9]+)?)").matcher(text);
            if (m.find()) {
                Integer rating = Math.round((float) Double.parseDouble(m.group(2)));
                aspects.put(capitalize(m.group(1)), rating);
            }
        }
        return aspects;
    }

    private String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1).toLowerCase();
    }

    private String textOf(Element element) {
        if (element == null) {
            return null;
        }
        String text = element.text();
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private long tripadvisorId(String url) {
        if (url == null) {
            return 0L;
        }
        Matcher matcher = TRIPADVISOR_ID_PATTERN.matcher(url);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : 0L;
    }

    private Integer parseInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
