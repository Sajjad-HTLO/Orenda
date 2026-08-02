package com.sajad.AITP.tripadvisor.crawler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import com.sajad.AITP.tripadvisor.config.TripadvisorCrawlerProperties;
import com.sajad.AITP.tripadvisor.model.CrawlPage;
import com.sajad.AITP.tripadvisor.model.HotelListing;
import com.sajad.AITP.tripadvisor.model.ListingCrawlResult;
import com.sajad.AITP.tripadvisor.model.ListingParseResult;
import com.sajad.AITP.tripadvisor.parser.ListingParser;
import com.sajad.AITP.tripadvisor.repository.HotelRepository;
import com.sajad.AITP.tripadvisor.repository.PageRepository;
import com.sajad.AITP.tripadvisor.util.RandomDelay;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

@Slf4j
@Component
@ConditionalOnProperty(name = "tripadvisor.crawler.enabled", havingValue = "true")
public class ListingWorker {

    private final TripadvisorCrawlerProperties properties;
    private final ListingParser listingParser;
    private final HotelRepository hotelRepository;
    private final PageRepository pageRepository;
    private final RandomDelay randomDelay;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ListingWorker(
            TripadvisorCrawlerProperties properties,
            ListingParser listingParser,
            HotelRepository hotelRepository,
            PageRepository pageRepository,
            RandomDelay randomDelay) {
        this.properties = properties;
        this.listingParser = listingParser;
        this.hotelRepository = hotelRepository;
        this.pageRepository = pageRepository;
        this.randomDelay = randomDelay;
    }

    public ListingCrawlResult crawl(CrawlPage crawlPage) {
        long startedAt = System.currentTimeMillis();
        logProgress("START", crawlPage, startedAt,
                "Crawler received URL. singlePageOnly=%s".formatted(properties.singlePageOnly()));

        if (!properties.singlePageOnly()) {
            Integer completedHotelCount = pageRepository.resumableCompletedHotelCount(crawlPage.offset());
            if (completedHotelCount != null) {
                logProgress("SKIP", crawlPage, startedAt,
                        "Already completed from persisted progress. previousHotelCount=%d".formatted(completedHotelCount));
                return ListingCrawlResult.skipped(crawlPage, completedHotelCount);
            }
            pageRepository.markInProgress(crawlPage.offset(), crawlPage.url());
            logProgress("PROGRESS_DB", crawlPage, startedAt, "Marked page as IN_PROGRESS in tripadvisor_crawled_pages");
        } else {
            logProgress("VERIFY_MODE", crawlPage, startedAt,
                    "Ignoring persisted offsets and disabling poi/progress writes for extraction verification");
        }

        // Retry up to 3 times with fresh profiles when DataDome blocks us
        int maxRetries = 3;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            Path userDataDir = ensureUserDataDir(attempt);
            String chromePath = resolveChromeExecutable();
            boolean headed = !properties.headless();
            logProgress("BROWSER_OPEN", crawlPage, startedAt,
                    "Opening Chrome. attempt=%d/%d headed=%s chromePath=%s userDataDir=%s"
                            .formatted(attempt, maxRetries, headed, chromePath, userDataDir));

            java.util.List<String> launchArgs = new java.util.ArrayList<>(antiDetectionArgs(headed));
            BrowserType.LaunchPersistentContextOptions launchOptions =
                    new BrowserType.LaunchPersistentContextOptions()
                            .setHeadless(!headed)
                            .setArgs(launchArgs)
                            .setUserAgent(properties.userAgent())
                            .setLocale("en-US")
                            .setTimezoneId("Europe/Istanbul")
                            .setViewportSize(1366, 768)
                            .setExtraHTTPHeaders(MapUtils.headers());
            if (chromePath != null) {
                launchOptions.setExecutablePath(Path.of(chromePath));
            }
            try (Playwright playwright = Playwright.create();
                 BrowserContext context = playwright.chromium().launchPersistentContext(
                         userDataDir, launchOptions)) {
                randomDelay.pause();
                Page page = context.pages().isEmpty() ? context.newPage() : context.pages().get(0);
                page.setDefaultNavigationTimeout(properties.navigationTimeoutMs());
                injectStealthScripts(page);

                logProgress("NAVIGATE_START", crawlPage, startedAt, "Navigating to Tripadvisor listing page");
                page.navigate(crawlPage.url(), new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                        .setTimeout(properties.navigationTimeoutMs()));
                logProgress("DOM_READY", crawlPage, startedAt, "DOMContentLoaded reached");

                try {
                    page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions()
                            .setTimeout(Math.min(properties.navigationTimeoutMs(), 15000)));
                    logProgress("NETWORK_IDLE", crawlPage, startedAt, "Network idle reached");
                } catch (Exception e) {
                    log.warn("TRIPADVISOR_PROGRESS step=NETWORK_IDLE_TIMEOUT offset={} url={} elapsedMs={} warning={}",
                            crawlPage.offset(), crawlPage.url(), elapsedMs(startedAt), e.getMessage());
                }

                String html = page.content();
                String title = page.title();
                int htmlChars = html == null ? 0 : html.length();
                int htmlBytes = byteSize(html);
                String htmlSha256 = sha256(html);
                int hotelReviewOccurrences = countOccurrences(html, "Hotel_Review-");
                int captchaOccurrences = countOccurrencesIgnoreCase(html, "captcha");
                int accessDeniedOccurrences = countOccurrencesIgnoreCase(html, "access denied");
                int securityCheckOccurrences = countOccurrencesIgnoreCase(html, "security check");
                int botOccurrences = countOccurrencesIgnoreCase(html, "bot");
                boolean isDataDome = isDataDomeBlock(html);
                String htmlSnapshotPath = saveHtmlSnapshot(crawlPage, html);
                String htmlPreview = preview(html, 600);
                log.info("TRIPADVISOR_FETCHED_DATA url={} offset={} title='{}' htmlChars={} htmlBytes={} htmlSha256={} hotelReviewOccurrences={} captchaOccurrences={} accessDeniedOccurrences={} securityCheckOccurrences={} botOccurrences={} isDataDome={} snapshotPath={} elapsedMs={}",
                        crawlPage.url(), crawlPage.offset(), title, htmlChars, htmlBytes, htmlSha256, hotelReviewOccurrences,
                        captchaOccurrences, accessDeniedOccurrences, securityCheckOccurrences, botOccurrences, isDataDome, htmlSnapshotPath, elapsedMs(startedAt));
                log.info("TRIPADVISOR_HTML_PREVIEW url={} offset={} preview={}", crawlPage.url(), crawlPage.offset(), htmlPreview);

                if (isDataDome && attempt < maxRetries) {
                    log.warn("TRIPADVISOR_DATADOME_BLOCKED attempt={}/{} url={} offset={} — DataDome CAPTCHA detected. "
                            + "Deleting contaminated profile and retrying with fresh browser state...",
                            attempt, maxRetries, crawlPage.url(), crawlPage.offset());
                    deleteUserDataDir(userDataDir);
                    randomDelay.pauseLonger();
                    continue;
                }

                ListingParseResult parseResult = listingParser.parse(html, crawlPage.url());
                String hotelsJson = serializeHotels(parseResult.hotels());
                int jsonChars = hotelsJson.length();
                int jsonBytes = byteSize(hotelsJson);

                log.info("TRIPADVISOR_EXTRACTION_SUMMARY url={} offset={} hotelCount={} htmlBytes={} jsonChars={} jsonBytes={} hotelReviewOccurrences={} snapshotPath={} elapsedMs={}",
                        crawlPage.url(), crawlPage.offset(), parseResult.hotelCount(), htmlBytes, jsonChars, jsonBytes,
                        hotelReviewOccurrences, htmlSnapshotPath, elapsedMs(startedAt));
                if (parseResult.hotelCount() == 0) {
                    log.warn("TRIPADVISOR_ZERO_EXTRACTION_DIAGNOSTIC url={} offset={} reason='Rendered HTML contained no parseable Hotel_Review links' htmlBytes={} hotelReviewOccurrences={} title='{}' snapshotPath={} htmlPreview={}",
                            crawlPage.url(), crawlPage.offset(), htmlBytes, hotelReviewOccurrences, title, htmlSnapshotPath, htmlPreview);
                }
                logHotelSummary(parseResult.hotels());
                log.info("TRIPADVISOR_EXTRACTED_HOTELS_JSON url={} offset={} hotelCount={} jsonBytes={} hotelsJson={}",
                        crawlPage.url(), crawlPage.offset(), parseResult.hotelCount(), jsonBytes, hotelsJson);

                int persistedRows = 0;
                if (!properties.singlePageOnly()) {
                    persistedRows = hotelRepository.upsertListings(parseResult.hotels());
                    pageRepository.markCompleted(crawlPage.offset(), crawlPage.url(), parseResult.hotelCount());
                    logProgress("PERSISTED", crawlPage, startedAt,
                            "Persisted extracted hotels to poi and marked progress complete. affectedRows=%d".formatted(persistedRows));
                } else {
                    logProgress("PERSIST_SKIPPED", crawlPage, startedAt,
                            "Single-page verification mode: skipped poi/progress persistence. extractedHotels=%d".formatted(parseResult.hotelCount()));
                }

                log.info("TRIPADVISOR_DONE url={} offset={} extractedHotels={} persistedRows={} htmlBytes={} jsonBytes={} totalElapsedMs={}",
                        crawlPage.url(), crawlPage.offset(), parseResult.hotelCount(), persistedRows, htmlBytes, jsonBytes, elapsedMs(startedAt));
                return ListingCrawlResult.success(crawlPage, parseResult.hotelCount());
            } catch (Exception e) {
                if (attempt < maxRetries) {
                    log.warn("TRIPADVISOR_RETRY attempt={}/{} url={} offset={} error={}",
                            attempt, maxRetries, crawlPage.url(), crawlPage.offset(), e.getMessage());
                    randomDelay.pauseLonger();
                } else {
                    if (!properties.singlePageOnly()) {
                        pageRepository.markFailed(crawlPage.offset(), crawlPage.url(), e);
                    }
                    log.error("TRIPADVISOR_FAILED url={} offset={} elapsedMs={} error={}",
                            crawlPage.url(), crawlPage.offset(), elapsedMs(startedAt), e.getMessage(), e);
                    return ListingCrawlResult.failed(crawlPage, e);
                }
            }
        }
        // Should never reach here, but just in case
        return ListingCrawlResult.failed(crawlPage, new IllegalStateException("Exhausted all retry attempts"));
    }

    private void logProgress(String step, CrawlPage crawlPage, long startedAt, String message) {
        log.info("TRIPADVISOR_PROGRESS step={} offset={} url={} elapsedMs={} message={}",
                step, crawlPage.offset(), crawlPage.url(), elapsedMs(startedAt), message);
    }

    private void logHotelSummary(List<HotelListing> hotels) {
        if (hotels.isEmpty()) {
            log.warn("TRIPADVISOR_HOTEL_SUMMARY hotelCount=0 message=No hotels extracted from rendered page");
            return;
        }
        for (int i = 0; i < hotels.size(); i++) {
            HotelListing hotel = hotels.get(i);
            log.info("TRIPADVISOR_HOTEL_SUMMARY index={} id={} name='{}' url={}",
                    i + 1, hotel.tripadvisorId(), hotel.name(), hotel.url());
        }
    }

    private String serializeHotels(List<HotelListing> hotels) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(hotels);
        } catch (Exception e) {
            log.warn("Failed to serialize Tripadvisor extracted hotels to JSON. error={}", e.getMessage());
            return "[]";
        }
    }

    private int byteSize(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private String saveHtmlSnapshot(CrawlPage crawlPage, String html) {
        try {
            Files.createDirectories(Path.of("data", "tripadvisor-debug"));
            String safeOffset = String.valueOf(crawlPage.offset());
            Path path = Path.of("data", "tripadvisor-debug", "tripadvisor-offset-" + safeOffset + ".html");
            Files.writeString(path, html == null ? "" : html, StandardCharsets.UTF_8);
            return path.toString();
        } catch (Exception e) {
            log.warn("Failed to save Tripadvisor HTML snapshot. offset={}, error={}", crawlPage.offset(), e.getMessage());
            return "<not-saved>";
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return "<sha256-error>";
        }
    }

    private int countOccurrences(String value, String needle) {
        if (value == null || needle == null || needle.isBlank()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private int countOccurrencesIgnoreCase(String value, String needle) {
        if (value == null || needle == null) {
            return 0;
        }
        return countOccurrences(value.toLowerCase(Locale.ROOT), needle.toLowerCase(Locale.ROOT));
    }

    private String preview(String value, int maxChars) {
        if (value == null || value.isBlank()) {
            return "<empty>";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars) + "...";
    }

    private long elapsedMs(long startedAt) {
        return System.currentTimeMillis() - startedAt;
    }

    /**
     * Creates and returns the path to a persistent user data directory.
     * When attempt > 1, uses a fresh directory to avoid DataDome cookie contamination
     * from previous blocked attempts.
     */
    private Path ensureUserDataDir(int attempt) {
        try {
            Path dir = attempt <= 1
                    ? Path.of("data", "tripadvisor-browser-profile")
                    : Path.of("data", "tripadvisor-browser-profile-" + attempt);
            Files.createDirectories(dir);
            return dir;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create persistent browser profile directory", e);
        }
    }

    /**
     * Deletes a contaminated browser profile directory so the next retry
     * starts with a completely fresh state.
     */
    private void deleteUserDataDir(Path dir) {
        try {
            if (Files.exists(dir)) {
                try (var stream = Files.walk(dir)) {
                    stream.sorted(java.util.Comparator.reverseOrder())
                            .forEach(p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (Exception ignored) {
                                }
                            });
                }
                log.info("Deleted contaminated browser profile: {}", dir);
            }
        } catch (Exception e) {
            log.warn("Failed to delete browser profile directory {}: {}", dir, e.getMessage());
        }
    }

    /**
     * Detects whether the page HTML is a DataDome CAPTCHA challenge rather than
     * the actual Tripadvisor content. DataDome pages are ~1.5KB, contain
     * geo.captcha-delivery.com, and have no Hotel_Review links.
     */
    private boolean isDataDomeBlock(String html) {
        if (html == null || html.isBlank()) {
            return false;
        }
        return html.contains("geo.captcha-delivery.com")
                && html.contains("DataDome")
                && html.length() < 5000;
    }

    /**
     * Resolves the path to a system-installed Google Chrome executable.
     * DataDome fingerprints the TLS stack (JA3/JA4) of the browser — Playwright's
     * bundled Chromium has a different TLS fingerprint than real Google Chrome,
     * so DataDome blocks it at the network level before any JavaScript runs.
     * Using the system Chrome bypasses this because its TLS fingerprint matches
     * what real users have.
     */
    private static String resolveChromeExecutable() {
        // Common paths for Google Chrome on Linux
        String[] candidates = {
                "/usr/bin/google-chrome-stable",
                "/usr/bin/google-chrome",
                "/usr/bin/chromium-browser",
                "/usr/bin/chromium",
                "/snap/bin/chromium"
        };
        for (String candidate : candidates) {
            if (java.nio.file.Files.isExecutable(java.nio.file.Path.of(candidate))) {
                return candidate;
            }
        }
        // Fall back to Playwright's bundled Chromium if no system Chrome found
        log.warn("No system Google Chrome found at common paths. Falling back to Playwright's bundled Chromium (may trigger DataDome).");
        return null;
    }

    /**
     * Chromium launch arguments.
     * When headed=false, includes --headless=new (Chrome 112+ new headless mode
     * which shares the same rendering path as headed Chrome).
     * When headed=true, runs as a normal visible browser — the most reliable
     * way to bypass anti-bot systems.
     *
     * IMPORTANT: Do NOT include --disable-blink-features=AutomationControlled —
     * anti-bot services actively check for this flag.
     */
    private static java.util.List<String> antiDetectionArgs(boolean headed) {
        java.util.List<String> args = new java.util.ArrayList<>(java.util.List.of(
                "--no-sandbox",
                "--disable-setuid-sandbox",
                "--disable-dev-shm-usage",
                "--no-first-run",
                "--no-default-browser-check",
                "--password-store=basic",
                "--use-mock-keychain",
                "--disable-background-timer-throttling",
                "--disable-backgrounding-occluded-windows",
                "--disable-renderer-backgrounding",
                "--disable-features=Translate",
                "--disable-sync",
                "--disable-default-apps",
                "--disable-extensions",
                "--disable-popup-blocking",
                "--disable-prompt-on-repost",
                "--disable-hang-monitor",
                "--metrics-recording-only",
                "--mute-audio"
        ));
        if (!headed) {
            args.add("--headless=new");
        }
        return args;
    }

    /**
     * Injects JavaScript into every frame to hide Playwright automation markers.
     * DataDome checks for navigator.webdriver, chrome.runtime, and other properties
     * that reveal headless/automated browsers.
     */
    private void injectStealthScripts(Page page) {
        page.addInitScript("""
                // --- Remove navigator.webdriver flag (the #1 bot detection signal) ---
                Object.defineProperty(navigator, 'webdriver', {
                    get: () => undefined
                });

                // --- Spoof chrome.runtime to appear as regular Chrome ---
                window.chrome = {
                    runtime: {},
                    loadTimes: function() {},
                    csi: function() {},
                    app: {}
                };

                // --- Overwrite permissions to avoid detection ---
                const originalQuery = window.navigator.permissions.query;
                window.navigator.permissions.query = (parameters) => (
                    parameters.name === 'notifications' ?
                        Promise.resolve({ state: Notification.permission }) :
                        originalQuery(parameters)
                );

                // --- Overwrite plugins to look like a normal browser ---
                Object.defineProperty(navigator, 'plugins', {
                    get: () => {
                        const plugins = [
                            { name: 'Chrome PDF Plugin', filename: 'internal-pdf-viewer', description: 'Portable Document Format' },
                            { name: 'Chrome PDF Viewer', filename: 'mhjfbmdgcfjbbpaeojofohoefgiehjai', description: '' },
                            { name: 'Native Client', filename: 'internal-nacl-plugin', description: '' }
                        ];
                        plugins.item = (i) => plugins[i];
                        plugins.namedItem = (name) => plugins.find(p => p.name === name);
                        plugins.refresh = () => {};
                        return plugins;
                    }
                });

                // --- Overwrite languages ---
                Object.defineProperty(navigator, 'languages', {
                    get: () => ['en-US', 'en']
                });

                // --- Overwrite hardwareConcurrency ---
                Object.defineProperty(navigator, 'hardwareConcurrency', {
                    get: () => 8
                });

                // --- Overwrite deviceMemory ---
                Object.defineProperty(navigator, 'deviceMemory', {
                    get: () => 8
                });

                // --- Overwrite platform ---
                Object.defineProperty(navigator, 'platform', {
                    get: () => 'Linux x86_64'
                });

                // --- Overwrite vendor ---
                Object.defineProperty(navigator, 'vendor', {
                    get: () => 'Google Inc.'
                });

                // --- Overwrite connection ---
                if (navigator.connection) {
                    Object.defineProperty(navigator.connection, 'rtt', {
                        get: () => 100
                    });
                }

                // --- Remove PhantomJS traces ---
                delete window.callPhantom;
                delete window._phantom;
                delete window.__phantomas;

                // --- Spoof WebGL vendor/renderer ---
                const getParameter = WebGLRenderingContext.prototype.getParameter;
                WebGLRenderingContext.prototype.getParameter = function(parameter) {
                    if (parameter === 37445) {
                        return 'Intel Inc.';
                    }
                    if (parameter === 37446) {
                        return 'Intel Iris OpenGL Engine';
                    }
                    return getParameter.call(this, parameter);
                };

                // --- Overwrite screen dimensions ---
                Object.defineProperty(screen, 'availWidth', { get: () => 1366 });
                Object.defineProperty(screen, 'availHeight', { get: () => 768 });
                Object.defineProperty(screen, 'width', { get: () => 1366 });
                Object.defineProperty(screen, 'height', { get: () => 768 });
                Object.defineProperty(screen, 'colorDepth', { get: () => 24 });
                Object.defineProperty(screen, 'pixelDepth', { get: () => 24 });

                // --- Overwrite window.outerWidth/Height ---
                Object.defineProperty(window, 'outerWidth', { get: () => 1366 });
                Object.defineProperty(window, 'outerHeight', { get: () => 768 });

                // --- Overwrite innerWidth/innerHeight ---
                Object.defineProperty(window, 'innerWidth', { get: () => 1366 });
                Object.defineProperty(window, 'innerHeight', { get: () => 768 });

                // --- Overwrite Notification to avoid detection ---
                if (window.Notification) {
                    const originalPermission = Object.getOwnPropertyDescriptor(Notification, 'permission');
                    Object.defineProperty(Notification, 'permission', {
                        ...originalPermission,
                        get: () => 'default'
                    });
                }
                """);
    }

    private static final class MapUtils {
        private static java.util.Map<String, String> headers() {
            return java.util.Map.of(
                    "Accept-Language", "en-US,en;q=0.9",
                    "Upgrade-Insecure-Requests", "1"
            );
        }
    }
}
