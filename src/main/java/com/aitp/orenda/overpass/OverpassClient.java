package com.aitp.orenda.overpass;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Queries the Overpass API (free, no auth) for tourist-relevant POIs in Turkey.
 * <p>
 * Overpass is a read-only API that queries OSM data. Unlike a PBF import, it
 * allows targeted queries for specific tags, regions, or time-based diffs.
 * <p>
 * Rate limit: ~10,000 requests/day, reasonable query complexity limits.
 * We wait 5 seconds between category queries to be polite.
 *
 * @see <a href="https://overpass-api.de/api/interpreter">Overpass API</a>
 */
@Slf4j
@Service
public class OverpassClient {

    private static final String OVERPASS_ENDPOINT = "https://overpass-api.de/api/interpreter";
    private static final String USER_AGENT = "AITP-TravelOS/0.1 (POI import; contact: dev@local)";

    /**
     * Turkey's approximate bounding box.
     * West: 26°E, South: 36°N, East: 45°E, North: 42°N
     */
    private static final String TURKEY_BBOX = "36.0,26.0,42.0,45.0";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public OverpassClient(
            @Value("${overpass.import.query-timeout-seconds:180}") int queryTimeoutSeconds) {
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
     * Returns all configured Overpass QL queries for tourist POI categories in Turkey.
     * Each query targets a specific OSM tag and returns matching elements within
     * Turkey's bounding box.
     */
    public List<CategoryQuery> getCategoryQueries() {
        return List.of(
                // ── Tourism ──────────────────────────────────────────────────
                tagQuery("tourism", "museum", "culture", "museum"),
                tagQuery("tourism", "gallery", "culture", "gallery"),
                tagQuery("tourism", "artwork", "culture", "artwork"),
                tagQuery("tourism", "aquarium", "culture", "aquarium"),
                tagQuery("tourism", "attraction", "attraction", "attraction"),
                tagQuery("tourism", "viewpoint", "attraction", "viewpoint"),
                tagQuery("tourism", "zoo", "leisure", "zoo"),
                tagQuery("tourism", "theme_park", "leisure", "theme_park"),
                tagQuery("tourism", "picnic_site", "leisure", "picnic_site"),

                // ── Historic ─────────────────────────────────────────────────
                tagQuery("historic", "castle", "historic", "castle"),
                tagQuery("historic", "ruins", "historic", "ruins"),
                tagQuery("historic", "monument", "historic", "monument"),
                tagQuery("historic", "memorial", "historic", "memorial"),
                tagQuery("historic", "archaeological_site", "historic", "archaeological_site"),
                tagQuery("historic", "fort", "historic", "fort"),
                tagQuery("historic", "citadel", "historic", "citadel"),
                tagQuery("historic", "palace", "historic", "palace"),
                tagQuery("historic", "amphitheatre", "historic", "amphitheatre"),
                tagQuery("historic", "aqueduct", "historic", "aqueduct"),
                tagQuery("historic", "tower", "historic", "tower"),
                tagQuery("historic", "mausoleum", "historic", "mausoleum"),
                tagQuery("historic", "caravanserai", "historic", "caravanserai"),
                tagQuery("historic", "church", "historic", "church"),
                tagQuery("historic", "mosque", "historic", "mosque"),
                tagQuery("historic", "synagogue", "historic", "synagogue"),
                tagQuery("historic", "bridge", "historic", "bridge"),
                tagQuery("historic", "lighthouse", "historic", "lighthouse"),
                tagQuery("historic", "battlefield", "historic", "battlefield"),
                tagQuery("historic", "city_gate", "historic", "city_gate"),
                tagQuery("historic", "citywalls", "historic", "city_walls"),
                tagQuery("historic", "obelisk", "historic", "obelisk"),
                tagQuery("historic", "tomb", "historic", "tomb"),
                tagQuery("historic", "wayside_shrine", "historic", "wayside_shrine"),
                tagQuery("historic", "wreck", "historic", "wreck"),

                // ── Amenity ──────────────────────────────────────────────────
                tagQuery("amenity", "place_of_worship", "culture", "place_of_worship"),
                tagQuery("amenity", "theatre", "culture", "theatre"),
                tagQuery("amenity", "arts_centre", "culture", "arts_centre"),
                tagQuery("amenity", "library", "culture", "library"),
                tagQuery("amenity", "cinema", "entertainment", "cinema"),
                tagQuery("amenity", "nightclub", "entertainment", "nightclub"),
                tagQuery("amenity", "marketplace", "shopping", "marketplace"),
                tagQuery("amenity", "bazaar", "shopping", "bazaar"),
                tagQuery("amenity", "spa", "wellness", "spa"),

                // ── Natural ──────────────────────────────────────────────────
                tagQuery("natural", "beach", "nature", "beach"),
                tagQuery("natural", "cave_entrance", "nature", "cave"),
                tagQuery("natural", "volcano", "nature", "volcano"),
                tagQuery("natural", "hot_spring", "nature", "hot_spring"),
                tagQuery("natural", "spring", "nature", "spring"),
                tagQuery("natural", "peak", "nature", "peak"),
                tagQuery("natural", "valley", "nature", "valley"),
                tagQuery("natural", "waterfall", "nature", "waterfall"),

                // ── Leisure ──────────────────────────────────────────────────
                tagQuery("leisure", "park", "nature", "park"),
                tagQuery("leisure", "garden", "nature", "garden"),
                tagQuery("leisure", "nature_reserve", "nature", "nature_reserve"),
                tagQuery("leisure", "marina", "leisure", "marina"),
                tagQuery("leisure", "stadium", "leisure", "stadium"),
                tagQuery("leisure", "water_park", "leisure", "water_park"),
                tagQuery("leisure", "beach_resort", "leisure", "beach_resort"),

                // ── Boundary protected areas ─────────────────────────────────
                protectedAreaQuery("national_park", "nature", "national_park"),
                protectedAreaQuery("nature_reserve", "nature", "nature_reserve"),

                // ── Railway (tourist/heritage railways) ──────────────────────
                railwayQuery(),

                // ── Waterway ─────────────────────────────────────────────────
                tagQuery("waterway", "waterfall", "nature", "waterfall"),

                // ── Man-made ─────────────────────────────────────────────────
                tagQuery("man_made", "lighthouse", "historic", "lighthouse"),
                tagQuery("man_made", "tower", "historic", "tower"),
                tagQuery("man_made", "watermill", "historic", "watermill"),
                tagQuery("man_made", "windmill", "historic", "windmill"),
                tagQuery("man_made", "obelisk", "historic", "obelisk"),

                // ── Building ─────────────────────────────────────────────────
                tagQuery("building", "mosque", "historic", "mosque"),
                tagQuery("building", "church", "historic", "church"),
                tagQuery("building", "synagogue", "historic", "synagogue"),
                tagQuery("building", "cathedral", "historic", "cathedral"),
                tagQuery("building", "temple", "historic", "temple"),

                // ── Religion ─────────────────────────────────────────────────
                tagQuery("religion", "place_of_worship", "culture", "place_of_worship"),

                // ── Shop ─────────────────────────────────────────────────────
                tagQuery("shop", "mall", "shopping", "mall"),

                // ── Aeroway (for airport-adjacent attractions) ───────────────
                tagQuery("aeroway", "terminal", "transport", "airport_terminal")
        );
    }

    // ── Query execution ───────────────────────────────────────────────────────

    /**
     * Executes a single Overpass QL query and returns the raw JSON response as a
     * list of element nodes. Each node represents one OSM element with its tags
     * and geometry.
     */
    public List<JsonNode> executeQuery(String overpassQL) throws InterruptedException {
        int maxRetries = 3;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                if (attempt > 0) {
                    long backoff = (long) Math.pow(2, attempt) * 3000;
                    log.info("  ⏳ Retrying Overpass query in {}ms (attempt {}/{})",
                            backoff, attempt + 1, maxRetries);
                    Thread.sleep(backoff);
                }

                Instant start = Instant.now();
                String response = restClient.post()
                        .uri(OVERPASS_ENDPOINT)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .body("data=" + URLEncoder.encode(overpassQL, StandardCharsets.UTF_8))
                        .retrieve()
                        .body(String.class);
                long elapsedMs = Duration.between(start, Instant.now()).toMillis();

                JsonNode root = objectMapper.readTree(response);
                JsonNode elements = root.get("elements");
                if (elements == null || !elements.isArray()) {
                    log.debug("  ✓ Query returned 0 elements ({}ms)", elapsedMs);
                    return List.of();
                }

                List<JsonNode> result = new ArrayList<>();
                for (JsonNode el : elements) {
                    result.add(el);
                }
                log.debug("  ✓ Query returned {} raw elements ({}ms, {}KB)",
                        result.size(), elapsedMs, response.length() / 1024);
                return result;

            } catch (Exception e) {
                log.warn("  ✗ Query attempt {} failed: {}", attempt + 1, e.getMessage());
                if (attempt == maxRetries - 1) {
                    log.error("  ✗ Query failed after {} attempts", maxRetries, e);
                    return List.of();
                }
            }
        }
        return List.of();
    }

    // ── Query builders ────────────────────────────────────────────────────────

    /**
     * Creates a CategoryQuery that searches for a specific OSM tag key=value
     * within Turkey's bounding box.
     */
    CategoryQuery tagQuery(String key, String value, String category, String subcategory) {
        String label = key + "=" + value;
        String ql = """
                [out:json][timeout:180];
                (
                  node["%s"="%s"](%s);
                  way["%s"="%s"](%s);
                  relation["%s"="%s"](%s);
                );
                out body center;
                """.formatted(
                key, value, TURKEY_BBOX,
                key, value, TURKEY_BBOX,
                key, value, TURKEY_BBOX);
        return new CategoryQuery(label, category, subcategory, ql, key, value);
    }

    /**
     * Creates a query for protected areas (boundary=national_park or
     * boundary=protected_area with specific protect_class).
     */
    CategoryQuery protectedAreaQuery(String subcategory, String category, String label) {
        String protectClass = subcategory.equals("national_park") ? "2" : "";
        String ql;
        if (subcategory.equals("national_park")) {
            ql = """
                    [out:json][timeout:180];
                    (
                      relation["boundary"="national_park"](%s);
                      relation["boundary"="protected_area"]["protect_class"="2"](%s);
                      relation["leisure"="nature_reserve"](%s);
                    );
                    out body center;
                    """.formatted(TURKEY_BBOX, TURKEY_BBOX, TURKEY_BBOX);
        } else {
            ql = """
                    [out:json][timeout:180];
                    (
                      relation["leisure"="nature_reserve"](%s);
                      relation["boundary"="protected_area"](%s);
                    );
                    out body center;
                    """.formatted(TURKEY_BBOX, TURKEY_BBOX);
        }
        return new CategoryQuery(label, category, subcategory, ql, null, null);
    }

    /**
     * Creates a query for tourist/heritage railways.
     */
    CategoryQuery railwayQuery() {
        String ql = """
                [out:json][timeout:180];
                (
                  relation["railway"="narrow_gauge"]["tourism"="yes"](%s);
                  relation["railway"="funicular"](%s);
                );
                out body center;
                """.formatted(TURKEY_BBOX, TURKEY_BBOX);
        return new CategoryQuery("tourist_railway", "transport", "tourist_railway", ql, "railway", null);
    }

    // ── Result parsing ────────────────────────────────────────────────────────

    /**
     * Parses a single Overpass JSON element into an {@link OverpassRawPoi}.
     *
     * @param element the JSON element from Overpass
     * @param query   the category query that produced this element
     * @return an OverpassRawPoi, or empty if the element has no usable data
     */
    public java.util.Optional<OverpassRawPoi> parseElement(
            JsonNode element, CategoryQuery query) {

        String type = element.get("type").asText();
        long id = element.get("id").asLong();

        // Extract coordinates
        double lat, lon;
        if ("node".equals(type)) {
            lat = element.get("lat").asDouble();
            lon = element.get("lon").asDouble();
        } else if (element.has("center")) {
            // Ways and relations use the 'center' field from 'out body center;'
            lat = element.get("center").get("lat").asDouble();
            lon = element.get("center").get("lon").asDouble();
        } else {
            // No coordinates available
            return java.util.Optional.empty();
        }

        // Parse tags
        JsonNode tags = element.get("tags");
        String nameTr = null;
        String nameEn = null;
        String wikidataId = null;
        String wikipediaTag = null;

        if (tags != null) {
            // Name: prefer name:tr, fall back to name, then name:en
            if (tags.has("name:tr")) {
                nameTr = tags.get("name:tr").asText();
            } else if (tags.has("name")) {
                nameTr = tags.get("name").asText();
            }
            if (tags.has("name:en")) {
                nameEn = tags.get("name:en").asText();
            }
            if (tags.has("wikidata")) {
                wikidataId = tags.get("wikidata").asText();
            }
            if (tags.has("wikipedia")) {
                wikipediaTag = tags.get("wikipedia").asText();
            }
        }

        // Require at least a name
        if (nameTr == null || nameTr.isBlank()) {
            return java.util.Optional.empty();
        }

        // Map element type to single-char osm_type
        String osmType = switch (type) {
            case "node" -> "N";
            case "way" -> "W";
            case "relation" -> "R";
            default -> null;
        };
        if (osmType == null) return java.util.Optional.empty();

        return java.util.Optional.of(OverpassRawPoi.builder()
                .osmId(id)
                .elementType(osmType)
                .lat(lat)
                .lon(lon)
                .nameTr(nameTr)
                .nameEn(nameEn)
                .category(query.category())
                .subcategory(query.subcategory())
                .osmTagKey(query.osmTagKey())
                .osmTagValue(query.osmTagValue())
                .wikidataId(wikidataId)
                .wikipediaTag(wikipediaTag)
                .queryLabel(query.label())
                .build());
    }

    // ── Category query descriptor ─────────────────────────────────────────────

    /**
     * Describes a single Overpass QL query for a specific tag/category combination.
     */
    public record CategoryQuery(
            String label,
            String category,
            String subcategory,
            String overpassQL,
            String osmTagKey,
            String osmTagValue
    ) {
    }
}
