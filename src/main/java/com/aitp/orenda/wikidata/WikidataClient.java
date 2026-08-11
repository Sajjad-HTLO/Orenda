package com.aitp.orenda.wikidata;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Queries the Wikidata SPARQL endpoint (free, no auth required) for tourist-relevant
 * POIs in Turkey. Each query targets a specific category (museum, castle, mosque, etc.)
 * and returns geo-tagged items with labels in Turkish and English.
 *
 * <p>Rate limit: Wikidata allows ~30 requests per minute from anonymous users.
 */
@Slf4j
@Service
public class WikidataClient {

    private static final String SPARQL_ENDPOINT = "https://query.wikidata.org/sparql";
    private static final String USER_AGENT = "AITP-TravelOS/0.1 (POI import; contact: dev@local)";

    /**
     * Q-ID for Turkey
     */
    private static final String Q_TURKEY = "Q43";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public WikidataClient(
            @Value("${wikidata.import.query-timeout-seconds:120}") int queryTimeoutSeconds) {
        this.objectMapper = new ObjectMapper();

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        this.restClient = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .defaultHeader("User-Agent", USER_AGENT)
                .build();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns all configured SPARQL queries for tourist POI categories in Turkey.
     */
    public List<CategoryQuery> getCategoryQueries() {
        return List.of(
                // ── Culture ──────────────────────────────────────────────────
                cat("museum", "culture", "museum", "Q33506"),
                cat("gallery", "culture", "gallery", "Q1007870"),
                cat("aquarium", "culture", "aquarium", "Q2281788"),
                cat("library", "culture", "library", "Q7075"),
                cat("theatre", "culture", "theatre", "Q24354"),
                cat("arts_centre", "culture", "arts_centre", "Q2190251"),
                cat("cinema", "entertainment", "cinema", "Q41253"),
                cat("nightclub", "entertainment", "nightclub", "Q622425"),

                // ── Historic ─────────────────────────────────────────────────
                cat("castle", "historic", "castle", "Q23413"),
                cat("ruins", "historic", "ruins", "Q109607"),
                cat("monument", "historic", "monument", "Q4989906"),
                cat("memorial", "historic", "memorial", "Q5003624"),
                cat("archaeological_site", "historic", "archaeological_site", "Q839954"),
                cat("fort", "historic", "fort", "Q1785071"),
                cat("palace", "historic", "palace", "Q16560"),
                cat("amphitheatre", "historic", "amphitheatre", "Q54811"),
                cat("aqueduct", "historic", "aqueduct", "Q474"),
                cat("tower", "historic", "tower", "Q12518"),
                cat("mausoleum", "historic", "mausoleum", "Q162875"),
                cat("caravanserai", "historic", "caravanserai", "Q186347"),
                cat("church", "historic", "church", "Q16970"),
                cat("mosque", "historic", "mosque", "Q32815"),
                cat("synagogue", "historic", "synagogue", "Q34627"),
                cat("hamam", "wellness", "turkish_bath", "Q190989"),

                // ── Nature ───────────────────────────────────────────────────
                cat("national_park", "nature", "national_park", "Q46169"),
                cat("nature_reserve", "nature", "nature_reserve", "Q179049"),
                cat("beach", "nature", "beach", "Q40080"),
                cat("cave", "nature", "cave", "Q35509"),
                cat("waterfall", "nature", "waterfall", "Q34038"),
                cat("hot_spring", "nature", "hot_spring", "Q177380"),
                cat("mountain", "nature", "peak", "Q8502"),
                cat("lake", "nature", "lake", "Q23397"),
                cat("valley", "nature", "valley", "Q39816"),
                cat("island", "nature", "island", "Q23442"),

                // ── Shopping / Leisure ───────────────────────────────────────
                cat("bazaar", "shopping", "bazaar", "Q330284"),
                cat("theme_park", "leisure", "theme_park", "Q2416723"),
                cat("zoo", "leisure", "zoo", "Q43501"),
                cat("stadium", "leisure", "stadium", "Q483110"),
                cat("marina", "leisure", "marina", "Q721207"),

                // ── Transport ────────────────────────────────────────────────
                cat("bridge", "historic", "bridge", "Q12280"),
                cat("lighthouse", "historic", "lighthouse", "Q39715")
        );
    }

    // ── Query execution ───────────────────────────────────────────────────────

    /**
     * Executes a single SPARQL query and returns raw binding maps.
     */
    public List<Map<String, WikidataSparqlResponse.Binding>> executeQuery(String sparql)
            throws InterruptedException {
        int maxRetries = 3;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                if (attempt > 0) {
                    long backoff = (long) Math.pow(2, attempt) * 1000;
                    Thread.sleep(backoff);
                }

                // Use POST with form-encoded body to avoid URL fragment issues
                // with SPARQL PREFIX URIs containing '#' characters.
                String response = restClient.post()
                        .uri(SPARQL_ENDPOINT)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .body("format=json&query=" + java.net.URLEncoder.encode(sparql,
                                java.nio.charset.StandardCharsets.UTF_8))
                        .retrieve()
                        .body(String.class);

                WikidataSparqlResponse parsed = objectMapper.readValue(
                        response, WikidataSparqlResponse.class);

                if (parsed.getResults() == null || parsed.getResults().getBindings() == null) {
                    return List.of();
                }
                return parsed.getResults().getBindings();

            } catch (Exception e) {
                log.warn("SPARQL query attempt {} failed: {}", attempt + 1, e.getMessage());
                if (attempt == maxRetries - 1) {
                    log.error("SPARQL query failed after {} attempts", maxRetries, e);
                    return List.of();
                }
            }
        }
        return List.of();
    }

    // ── Result parsing ────────────────────────────────────────────────────────

    /**
     * Parses a single SPARQL result binding into a {@link WikidataRawPoi}.
     */
    public Optional<WikidataRawPoi> parseBinding(
            Map<String, WikidataSparqlResponse.Binding> binding,
            String category, String subcategory) {

        String qid = bindingValue(binding, "item");
        if (qid == null) return Optional.empty();

        Double lat = bindingDouble(binding, "lat");
        Double lon = bindingDouble(binding, "lon");
        if (lat == null || lon == null) return Optional.empty();

        String labelTr = bindingValue(binding, "itemLabel");
        String labelEn = bindingValue(binding, "itemLabelEn");
        String descTr = bindingValue(binding, "itemDescription");
        String descEn = bindingValue(binding, "itemDescriptionEn");
        String image = bindingValue(binding, "image");
        String instanceOfQid = bindingValue(binding, "instanceOf");

        return Optional.of(WikidataRawPoi.builder()
                .qid(qid)
                .labelTr(labelTr)
                .labelEn(labelEn)
                .lat(lat)
                .lon(lon)
                .descriptionTr(descTr)
                .descriptionEn(descEn)
                .imageUrl(image)
                .category(category)
                .subcategory(subcategory)
                .instanceOfQid(instanceOfQid)
                .build());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String bindingValue(Map<String, WikidataSparqlResponse.Binding> binding, String var) {
        WikidataSparqlResponse.Binding b = binding.get(var);
        return b != null ? b.getValue() : null;
    }

    private Double bindingDouble(Map<String, WikidataSparqlResponse.Binding> binding, String var) {
        String val = bindingValue(binding, var);
        if (val == null) return null;
        try {
            return Double.parseDouble(val);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Creates a CategoryQuery with a properly constructed SPARQL query.
     * <p>
     * The query: finds all items in Turkey that are instances of the given type
     * (or its subtypes via wdt:P31/wdt:P279*), has geo-coordinates, and retrieves
     * labels in Turkish and English. Does NOT filter by language — we collect
     * both TR and EN labels via separate OPTIONAL bindings.
     */
    private CategoryQuery cat(String subcategory, String category, String label, String typeQid) {
        String sparql = buildQuery(typeQid);
        return new CategoryQuery(subcategory, category, label, sparql);
    }

    /**
     * Builds a SPARQL query that:
     * <ul>
     *   <li>Finds items in Turkey (wdt:P17 wd:Q43)</li>
     *   <li>That are instances of (or subclass of) the given type</li>
     *   <li>With geo-coordinates (wdt:P625)</li>
     *   <li>Fetches TR label via OPTIONAL, EN label via OPTIONAL</li>
     *   <li>Fetches TR/EN descriptions from wikibase:label service</li>
     *   <li>Optionally fetches an image (wdt:P18)</li>
     * </ul>
     * <p>
     * IMPORTANT: We do NOT FILTER by language in the WHERE clause — we want
     * ALL items, even those with only English labels. The wikibase:label service
     * is used only for descriptions (which auto-fallback through the language list).
     */
    private String buildQuery(String typeQid) {
        return """
                PREFIX wd: <http://www.wikidata.org/entity/>
                PREFIX wdt: <http://www.wikidata.org/prop/direct/>
                PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
                PREFIX bd: <http://www.bigdata.com/rdf#>
                PREFIX wikibase: <http://wikiba.se/ontology#>
                
                SELECT DISTINCT ?item ?itemLabel ?itemLabelEn ?itemDescription ?itemDescriptionEn
                       ?lat ?lon ?image ?instanceOf ?instanceOfLabel
                WHERE {
                  ?item wdt:P17 wd:%s;
                        wdt:P31/wdt:P279* wd:%s;
                        wdt:P625 ?coord.
                  BIND(xsd:float(STRBEFORE(STRAFTER(STR(?coord), "Point("), " ")) AS ?lon)
                  BIND(xsd:float(STRBEFORE(STRAFTER(STR(?coord), " "), ")")) AS ?lat)
                
                  OPTIONAL { ?item rdfs:label ?itemLabel FILTER(LANG(?itemLabel) = "tr") }
                  OPTIONAL { ?item rdfs:label ?itemLabelEn FILTER(LANG(?itemLabelEn) = "en") }
                
                  OPTIONAL { ?item wdt:P18 ?image. }
                  OPTIONAL { ?item wdt:P31 ?instanceOf. }
                
                  SERVICE wikibase:label {
                    bd:serviceParam wikibase:language "tr,en".
                    ?item schema:description ?itemDescription.
                    ?instanceOf rdfs:label ?instanceOfLabel.
                  }
                }
                LIMIT 1000
                """.formatted(Q_TURKEY, typeQid);
    }

    // ── Category query descriptor ─────────────────────────────────────────────

    /**
     * A named SPARQL query targeting a specific tourist POI category.
     */
    public record CategoryQuery(
            String subcategory,
            String category,
            String categoryLabel,
            String sparql) {
    }
}