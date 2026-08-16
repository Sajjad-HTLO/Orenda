package com.aitp.orenda.tripadvisor.crawler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Appends a human-readable record to a CSV file every time something stops the
 * Tripadvisor crawler, e.g. the IP being blocked, a DataDome captcha that could
 * not be resolved, or an unexpected error that aborts a crawl step.
 * <p>
 * Each event is one line: timestamp, type, stage, url, targetId, reason, title,
 * htmlBytes, phase. Writes are synchronized and append-only so concurrent hotel
 * detail workers never corrupt the file.
 * <p>
 * Failure to write is logged and swallowed — it must never abort the crawl.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "tripadvisor.crawler.enabled", havingValue = "true")
public class CrawlerStopEventLogger {

    public static final String TYPE_CAPTCHA = "CAPTCHA";
    public static final String TYPE_IP_BLOCKED = "IP_BLOCKED";
    public static final String TYPE_ERROR = "ERROR";
    public static final String TYPE_STOP = "STOP";

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String HEADER = "timestamp,type,stage,url,targetId,reason,title,htmlBytes,phase";

    private final Path logFile;
    private final Object lock = new Object();

    public CrawlerStopEventLogger(
            @Value("${tripadvisor.crawler.stop-log-file-path:data/tripadvisor-crawl-stops.csv}")
            String logFilePath) {
        this.logFile = Path.of(logFilePath);
    }

    /**
     * Records one stop event. Non-fatal: any write failure is logged and swallowed.
     */
    public void record(String type, String stage, String url, String targetId,
                       String reason, String title, int htmlBytes, String phase) {
        synchronized (lock) {
            try {
                if (logFile.getParent() != null && !Files.exists(logFile.getParent())) {
                    Files.createDirectories(logFile.getParent());
                }
                boolean newFile = !Files.exists(logFile);
                if (newFile) {
                    Files.writeString(logFile, HEADER + System.lineSeparator(), StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                }
                String line = String.join(",",
                        csv(TIMESTAMP.format(LocalDateTime.now())),
                        csv(type),
                        csv(stage),
                        csv(url),
                        csv(targetId),
                        csv(reason),
                        csv(title),
                        String.valueOf(htmlBytes),
                        csv(phase));
                Files.writeString(logFile, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                log.info("TRIPADVISOR_STOP_EVENT_RECORDED type={} stage={} url={} targetId={} reason='{}' file={}",
                        type, stage, url, targetId, reason, logFile);
            } catch (Exception e) {
                log.error("Failed to record Tripadvisor crawler stop event to {}. error={}",
                        logFile, e.getMessage(), e);
            }
        }
    }

    /**
     * Classifies a still-blocked page as either an IP ban or a captcha challenge
     * based on the markers present in the served HTML.
     */
    public String classifyBlockType(String html, String title) {
        if (html == null || html.isBlank()) {
            return TYPE_CAPTCHA;
        }
        String lower = html.toLowerCase(Locale.ROOT);
        if (lower.contains("access denied") || lower.contains("your ip")
                || lower.contains("forbidden") || lower.contains("has been blocked")
                || lower.contains("blocked from accessing")) {
            return TYPE_IP_BLOCKED;
        }
        if (lower.contains("captcha") || lower.contains("security check")
                || lower.contains("recaptcha") || lower.contains("verify you are human")) {
            return TYPE_CAPTCHA;
        }
        if (lower.contains("captcha-delivery.com") || "tripadvisor.com".equals(title)) {
            return TYPE_CAPTCHA;
        }
        return TYPE_CAPTCHA;
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        if (escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n") || escaped.contains("\r")) {
            return "\"" + escaped + "\"";
        }
        return escaped;
    }
}