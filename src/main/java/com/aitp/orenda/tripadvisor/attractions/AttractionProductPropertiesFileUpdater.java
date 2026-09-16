package com.aitp.orenda.tripadvisor.attractions;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Persists the current pagination URL back to the {@code application.properties}
 * file so that a restarted attraction crawler resumes from the next page instead
 * of starting over from the original base URL. The
 * {@code tripadvisor.crawler.attraction-products.base-url} line is rewritten in
 * place. Non-fatal: failures are logged and swallowed.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "tripadvisor.crawler.attraction-products.enabled", havingValue = "true")
public class AttractionProductPropertiesFileUpdater {

    private static final String BASE_URL_PREFIX = "tripadvisor.crawler.attraction-products.base-url";

    private final Path propertiesFilePath;

    public AttractionProductPropertiesFileUpdater(
            @Value("${tripadvisor.crawler.properties-file-path:src/main/resources/application.properties}")
            String propertiesFilePath) {
        this.propertiesFilePath = Path.of(propertiesFilePath);
    }

    /**
     * Rewrites the {@code tripadvisor.crawler.attraction-products.base-url} entry
     * in the properties file to {@code newUrl}. Non-fatal: any error is logged
     * and ignored so the crawl continues even if the file is read-only or
     * missing.
     */
    public void updateBaseUrl(String newUrl) {
        if (newUrl == null || newUrl.isBlank()) {
            log.warn("Tripadvisor attraction properties updater: refusing to write blank base-url. path={}", propertiesFilePath);
            return;
        }
        try {
            if (!Files.exists(propertiesFilePath)) {
                log.warn("Tripadvisor attraction properties updater: properties file not found, skipping base-url update. path={}",
                        propertiesFilePath);
                return;
            }
            List<String> lines = Files.readAllLines(propertiesFilePath, StandardCharsets.UTF_8);
            boolean updated = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.matches("^\\s*" + BASE_URL_PREFIX + "\\s*=.*$")) {
                    lines.set(i, BASE_URL_PREFIX + "=" + newUrl);
                    updated = true;
                    break;
                }
            }
            if (!updated) {
                log.warn("Tripadvisor attraction properties updater: no {} entry found, appending. path={}",
                        BASE_URL_PREFIX, propertiesFilePath);
                lines.add(BASE_URL_PREFIX + "=" + newUrl);
            }
            Files.write(propertiesFilePath, lines, StandardCharsets.UTF_8);
            log.info("Tripadvisor attraction properties updater: base-url updated to '{}' in {}", newUrl, propertiesFilePath);
        } catch (Exception e) {
            log.error("Tripadvisor attraction properties updater: failed to update base-url to '{}' in {}. error={}",
                    newUrl, propertiesFilePath, e.getMessage(), e);
        }
    }
}
