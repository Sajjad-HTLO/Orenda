package com.aitp.orenda.muzegov;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scrapes muze.gov.tr for museum/archaeological-site POIs.
 * <p>
 * The page embeds GeoJSON-like structures with coordinates and museum names:
 * <pre>
 *   "coordinates":["37.709379","28.727088"]},
 *   "properties":{"clusterCaption":"APHRODISIAS MUSEUM...",
 *   "balloonContentBody":"...SectionId=AFR01&DistId=AFR..."
 * </pre>
 */
@Slf4j
@Service
public class MuzeGovTrClient {

    private static final String MUSEUM_LIST_URL =
            "https://muze.gov.tr/muze-detay?SectionId=SGT01&DistId=SGT";
    private static final String USER_AGENT =
            "AITP-TravelOS/0.1 (POI import; contact: dev@local)";

    private static final Pattern MARKER = Pattern.compile(
            "\"coordinates\":\\[\"([^\"]+)\",\"([^\"]+)\"\\]" +
                    "\\},\"properties\":\\{\"clusterCaption\":\"([^\"]+)\"," +
                    "\"balloonContentBody\":\"[^\"]*SectionId=([A-Z0-9]+)&DistId=([A-Z0-9]+)");

    private final RestClient restClient;

    public MuzeGovTrClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.restClient = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .defaultHeader("User-Agent", USER_AGENT)
                .build();
    }

    public List<MuzeGovTrRawPoi> fetchAllMuseums() {
        log.info("Fetching museum list from muze.gov.tr...");
        String html;
        try {
            html = restClient.get()
                    .uri(MUSEUM_LIST_URL)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            log.error("Failed to fetch muze.gov.tr: {}", e.getMessage());
            return List.of();
        }

        List<MuzeGovTrRawPoi> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        Matcher m = MARKER.matcher(html);
        while (m.find()) {
            String lat = m.group(1);
            String lon = m.group(2);
            String name = m.group(3);
            String sid = m.group(4);
            String did = m.group(5);

            if (!seen.add(sid)) continue;

            try {
                results.add(MuzeGovTrRawPoi.builder()
                        .sectionId(sid)
                        .distId(did)
                        .nameTr(cleanName(name))
                        .lat(Double.parseDouble(lat))
                        .lon(Double.parseDouble(lon))
                        .build());
            } catch (NumberFormatException e) {
                log.debug("Skipping {}: bad coord", sid);
            }
        }

        log.info("Scraped {} museums from muze.gov.tr", results.size());
        return results;
    }

    private String cleanName(String name) {
        if (name == null) return "";
        return name
                .replace("&#xDC;", "Ü").replace("&#x131;", "ı")
                .replace("&#x11F;", "ğ").replace("&#xF6;", "ö")
                .replace("&#xE7;", "ç").replace("&#xC7;", "Ç")
                .replace("&#x15E;", "Ş").replace("&#x130;", "İ")
                .replace("&#xE2;", "â").replace("&#xFC;", "ü")
                .trim();
    }
}