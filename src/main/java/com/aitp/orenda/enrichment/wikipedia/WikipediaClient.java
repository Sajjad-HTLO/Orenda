package com.aitp.orenda.enrichment.wikipedia;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aitp.orenda.enrichment.PoiEnrichmentCandidate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class WikipediaClient {

    private static final String USER_AGENT = "AITP-TravelOS/0.1 (POI enrichment; contact: dev@local)";
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_BASE_DELAY_MS = 2000;

    private final RestClient restClient;
    private final RequestRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public WikipediaClient(RequestRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = new ObjectMapper();

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        this.restClient = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .defaultHeader("User-Agent", USER_AGENT)
                .build();
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull() || !node.isTextual()) return null;
        String value = node.asText();
        return value.isBlank() ? null : value;
    }

    public Optional<WikipediaSummary> lookup(PoiEnrichmentCandidate poi) throws InterruptedException {
        Map<String, Object> attrs = poi.getAttributes();
        if (attrs != null) {
            Optional<WikipediaSummary> fromTag = fetchFromOsmTag(attrs, "wikipedia");
            if (fromTag.isPresent()) return fromTag;

            Optional<WikipediaSummary> fromEn = fetchFromOsmTag(attrs, "wikipedia:en");
            if (fromEn.isPresent()) return fromEn;

            Optional<WikipediaSummary> fromTr = fetchFromOsmTag(attrs, "wikipedia:tr");
            if (fromTr.isPresent()) return fromTr;
        }

        // Try Turkish Wikipedia first (less rate-limited), then English
        if (poi.getNameTr() != null && !poi.getNameTr().isBlank()) {
            Optional<WikipediaSummary> hit = searchAndSummarize("tr", poi.getNameTr());
            if (hit.isPresent()) return hit;
        }

        if (poi.getNameEn() != null && !poi.getNameEn().isBlank()) {
            Optional<WikipediaSummary> hit = searchAndSummarize("en", poi.getNameEn());
            if (hit.isPresent()) return hit;
        }

        if (poi.getNameTr() != null && !poi.getNameTr().isBlank()) {
            Optional<WikipediaSummary> enHit = searchAndSummarize("en", poi.getNameTr());
            if (enHit.isPresent()) return enHit;
        }

        // fallback: resolve via Wikidata ID → Wikipedia article
        if (poi.getWikidataId() != null && !poi.getWikidataId().isBlank()) {
            Optional<WikipediaSummary> fromWikidata = fetchFromWikidataId(poi.getWikidataId());
            if (fromWikidata.isPresent()) return fromWikidata;
        }

        return Optional.empty();
    }

    private Optional<WikipediaSummary> fetchFromOsmTag(Map<String, Object> attrs, String key)
            throws InterruptedException {
        Object raw = attrs.get(key);
        if (raw == null) return Optional.empty();

        String value = raw.toString().trim();
        if (value.isBlank()) return Optional.empty();

        int sep = value.indexOf(':');
        if (sep <= 0 || sep >= value.length() - 1) return Optional.empty();

        String lang = value.substring(0, sep).trim();
        String title = value.substring(sep + 1).trim();
        return fetchSummary(lang, title);
    }

    private Optional<WikipediaSummary> searchAndSummarize(String lang, String query)
            throws InterruptedException {
        return withRetry(() -> {
            rateLimiter.acquire();

            try {
                String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
                ResponseEntity<String> response = restClient.get()
                        .uri("https://" + lang + ".wikipedia.org/w/api.php"
                                + "?action=opensearch&search=" + encodedQuery
                                + "&limit=1&namespace=0&format=json")
                        .retrieve()
                        .toEntity(String.class);

                HttpStatusCode status = response.getStatusCode();
                if (status.value() == 429) {
                    log.debug("Wikipedia search [{}] got 429 for query '{}'", lang, query);
                    return null; // signal retry
                }
                if (status.is4xxClientError()) {
                    return Optional.empty();
                }
                if (!status.is2xxSuccessful() || response.getBody() == null) {
                    return Optional.empty();
                }

                JsonNode root = objectMapper.readTree(response.getBody());
                if (!root.isArray() || root.size() < 2) {
                    return Optional.empty();
                }

                JsonNode titles = root.get(1);
                if (!titles.isArray() || titles.isEmpty()) {
                    return Optional.empty();
                }

                String title = titles.get(0).asText();
                return fetchSummary(lang, title);
            } catch (RestClientException e) {
                log.debug("Wikipedia search failed for [{}] {}: {}", lang, query, e.getMessage());
                return Optional.empty();
            } catch (Exception e) {
                log.debug("Wikipedia search parse failed for [{}] {}: {}", lang, query, e.getMessage());
                return Optional.empty();
            }
        });
    }

    private Optional<WikipediaSummary> fetchSummary(String lang, String title)
            throws InterruptedException {
        return withRetry(() -> {
            rateLimiter.acquire();

            try {
                String encodedTitle = URLEncoder.encode(title.replace(' ', '_'), StandardCharsets.UTF_8);
                ResponseEntity<String> response = restClient.get()
                        .uri("https://" + lang + ".wikipedia.org/api/rest_v1/page/summary/" + encodedTitle)
                        .retrieve()
                        .toEntity(String.class);

                HttpStatusCode status = response.getStatusCode();
                if (status.value() == 429) {
                    log.debug("Wikipedia summary [{}] got 429 for '{}'", lang, title);
                    return null; // signal retry
                }
                if (!status.is2xxSuccessful() || response.getBody() == null) {
                    return Optional.empty();
                }

                return parseSummaryBody(lang, title, response.getBody());
            } catch (HttpClientErrorException.NotFound e) {
                return Optional.empty();
            } catch (RestClientException e) {
                log.debug("Wikipedia summary not found for {}:{} ({})", lang, title, e.getMessage());
                return Optional.empty();
            }
        });
    }

    /**
     * Retry wrapper: retries up to MAX_RETRIES times when the action returns null
     * (which signals a retryable error like 429). Non-null results (including
     * Optional.empty()) are returned immediately.
     */
    private <T> T withRetry(RetryableAction<T> action) throws InterruptedException {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            T result = action.execute();
            if (result != null) {
                return result;
            }
            // 429 received — exponential backoff
            long delay = RETRY_BASE_DELAY_MS * (1L << attempt);
            log.debug("Retrying after {}ms (attempt {}/{})", delay, attempt + 1, MAX_RETRIES);
            Thread.sleep(delay);
        }
        log.debug("Exhausted retries after {} attempts", MAX_RETRIES);
        return null;
    }

    /**
     * Resolve a Wikidata ID (e.g. Q81952) to a Wikipedia article, preferring
     * Turkish Wikipedia, falling back to English.
     */
    private Optional<WikipediaSummary> fetchFromWikidataId(String wikidataId)
            throws InterruptedException {
        return withRetry(() -> {
            rateLimiter.acquire();

            try {
                String url = "https://www.wikidata.org/wiki/Special:EntityData/" + wikidataId + ".json";
                ResponseEntity<String> response = restClient.get()
                        .uri(url)
                        .retrieve()
                        .toEntity(String.class);

                HttpStatusCode status = response.getStatusCode();
                if (status.value() == 429) {
                    log.debug("Wikidata lookup got 429 for '{}'", wikidataId);
                    return null; // signal retry
                }
                if (!status.is2xxSuccessful() || response.getBody() == null) {
                    return Optional.empty();
                }

                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode entities = root.path("entities");
                JsonNode entity = entities.path(wikidataId);
                JsonNode sitelinks = entity.path("sitelinks");

                // prefer Turkish
                JsonNode trwiki = sitelinks.path("trwiki");
                if (!trwiki.isMissingNode()) {
                    String title = trwiki.path("title").asText();
                    if (!title.isBlank()) {
                        return fetchSummary("tr", title);
                    }
                }

                // fallback to English
                JsonNode enwiki = sitelinks.path("enwiki");
                if (!enwiki.isMissingNode()) {
                    String title = enwiki.path("title").asText();
                    if (!title.isBlank()) {
                        return fetchSummary("en", title);
                    }
                }

                return Optional.empty();
            } catch (RestClientException e) {
                log.debug("Wikidata lookup failed for {}: {}", wikidataId, e.getMessage());
                return Optional.empty();
            } catch (Exception e) {
                log.debug("Wikidata parse failed for {}: {}", wikidataId, e.getMessage());
                return Optional.empty();
            }
        });
    }

    private Optional<WikipediaSummary> parseSummaryBody(String lang, String title, String json) {
        JsonNode body;
        try {
            body = objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("Failed to parse Wikipedia summary for {}:{}", lang, title);
            return Optional.empty();
        }

        if (body.has("type") && body.get("type").asText("").contains("not_found")) {
            return Optional.empty();
        }

        String extract = textOrNull(body.get("extract"));
        if (extract == null || extract.isBlank()) {
            return Optional.empty();
        }

        String pageUrl = textOrNull(body.path("content_urls").path("desktop").path("page"));
        String imageUrl = textOrNull(body.path("thumbnail").path("source"));
        String description = textOrNull(body.get("description"));
        String resolvedTitle = textOrNull(body.get("title"));

        return Optional.of(new WikipediaSummary(
                lang,
                resolvedTitle != null ? resolvedTitle : title,
                extract,
                description,
                pageUrl,
                imageUrl
        ));
    }

    @FunctionalInterface
    private interface RetryableAction<T> {
        T execute() throws InterruptedException;
    }
}
