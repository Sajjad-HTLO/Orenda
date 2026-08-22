package com.aitp.orenda.tripadvisor.restaurants;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitUntilState;
import com.aitp.orenda.tripadvisor.util.DiskSpaceGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Stage 1 worker for the restaurant crawler: opens the Tripadvisor restaurant
 * listing URL, waits out any DataDome challenge, extracts the rendered HTML and
 * hands it to {@link RestaurantListingParser} for restaurant-card extraction.
 * <p>
 * Mirrors the (working) hotel {@code ListingWorker} strategy — DataDome homepage
 * warm-up, real-content polling, reload/full-retry fallbacks and human-like
 * interaction — but is hard-wired to restaurant pages ({@code Restaurant_Review-}
 * links) and persists via {@link RestaurantRepository}.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "tripadvisor.crawler.restaurants.enabled", havingValue = "true")
public class RestaurantListingWorker {

    private static final String TRIPADVISOR_HOMEPAGE = "https://www.tripadvisor.com/";
    private static final String RESTAURANT_LINK_MARKER = "Restaurant_Review-";
    private static final String DATADOME_MARKER = "captcha-delivery.com";
    private static final String DATADOME_CHALLENGE_TITLE = "tripadvisor.com";

    private final RestaurantCrawlerProperties properties;
    private final RestaurantListingParser restaurantListingParser;
    private final RestaurantRepository restaurantRepository;
    private final DiskSpaceGuard diskSpaceGuard;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RestaurantListingWorker(
            RestaurantCrawlerProperties properties,
            RestaurantListingParser restaurantListingParser,
            RestaurantRepository restaurantRepository,
            DiskSpaceGuard diskSpaceGuard) {
        this.properties = properties;
        this.restaurantListingParser = restaurantListingParser;
        this.restaurantRepository = restaurantRepository;
        this.diskSpaceGuard = diskSpaceGuard;
    }

    public RestaurantCrawlResult crawl(String url) {
        long startedAt = System.currentTimeMillis();
        log.info("TRIPADVISOR_RESTAURANT_START url={} headless={} navigationTimeoutMs={}",
                url, properties.headless(), properties.navigationTimeoutMs());

        Path userDataDir = ensureUserDataDir();
        if (!diskSpaceGuard.hasEnoughSpace(userDataDir)) {
            log.error("TRIPADVISOR_RESTAURANT_DISK_FULL url={} message=Free disk space {} bytes is below minimum {} bytes. " +
                            "Skipping page to avoid Chromium 'Target crashed'.",
                    url, diskSpaceGuard.freeBytes(userDataDir), diskSpaceGuard.minFreeBytes());
            return RestaurantCrawlResult.failed(url,
                    new RuntimeException("Disk space too low to launch browser; free at least "
                            + diskSpaceGuard.minFreeBytes() + " bytes"));
        }
        String chromePath = resolveChromeExecutable();

        List<String> launchArgs = new java.util.ArrayList<>(minimalChromeArgs());
        if (properties.headless()) {
            launchArgs.add("");
        }

        BrowserType.LaunchPersistentContextOptions launchOptions =
                new BrowserType.LaunchPersistentContextOptions()
                        .setHeadless(false)
                        .setArgs(launchArgs)
                        .setUserAgent(properties.userAgent())
                        .setLocale("en-US")
                        .setTimezoneId("Europe/Istanbul")
                        .setViewportSize(1366, 768)
                        .setExtraHTTPHeaders(browserHeaders());
        if (chromePath != null) {
            launchOptions.setExecutablePath(Path.of(chromePath));
        }

        try (Playwright playwright = Playwright.create();
             BrowserContext context = playwright.chromium().launchPersistentContext(userDataDir, launchOptions)) {

            Page page = context.pages().isEmpty() ? context.newPage() : context.pages().get(0);
            page.setDefaultNavigationTimeout(properties.navigationTimeoutMs());
            injectStealthScripts(page);

            // === STEP 1: Homepage warmup — establish DataDome cookies ===
            log.info("TRIPADVISOR_RESTAURANT_WARMUP url={} message=Visiting Tripadvisor homepage for DataDome cookie warming", url);
            page = navigateSafely(context, page, TRIPADVISOR_HOMEPAGE, startedAt, "WARMUP");
            boolean warmupOk = waitForRealContent(page, startedAt, "WARMUP");
            if (warmupOk) {
                boolean hasDataDomeCookie = context.cookies().stream()
                        .anyMatch(cookie -> "datadome".equals(cookie.name));
                log.info("TRIPADVISOR_RESTAURANT_WARMUP_DONE url={} datadomeCookiePresent={}", url, hasDataDomeCookie);
                performHumanBehavior(page);
            } else {
                log.warn("TRIPADVISOR_RESTAURANT_WARMUP_WARNING url={} message=Homepage warmup may not have fully resolved", url);
            }
            pause();

            // === STEP 2: Navigate to the restaurant listing page ===
            log.info("TRIPADVISOR_RESTAURANT_NAVIGATE url={} message=Navigating to restaurant listing page", url);
            page = navigateSafely(context, page, url, startedAt, "LISTING");

            // === STEP 3: Wait for DataDome challenge to resolve and real content to appear ===
            log.info("TRIPADVISOR_RESTAURANT_WAIT_CONTENT url={} message=Waiting for restaurant page content (handling DataDome challenge if present)", url);
            boolean contentReady = waitForRealContent(page, startedAt, "LISTING");

            // === STEP 3b: Retry with reload if blocked ===
            if (!contentReady) {
                log.warn("TRIPADVISOR_RESTAURANT_RETRY_RELOAD url={} message=Content not found, reloading page (retry 1)", url);
                pause();
                try {
                    page.reload(new Page.ReloadOptions()
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                            .setTimeout(properties.navigationTimeoutMs()));
                } catch (Exception e) {
                    log.debug("Restaurant reload failed: {}", e.getMessage());
                }
                contentReady = waitForRealContent(page, startedAt, "RETRY1");
            }

            // === STEP 3c: Full retry — re-warmup then re-navigate ===
            if (!contentReady) {
                log.warn("TRIPADVISOR_RESTAURANT_RETRY_FULL url={} message=Content still not found, doing full re-warmup + re-navigate (retry 2)", url);
                pause();
                try {
                    page.navigate(TRIPADVISOR_HOMEPAGE, new Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                            .setTimeout(properties.navigationTimeoutMs()));
                    waitForRealContent(page, startedAt, "RETRY_WARMUP");
                    performHumanBehavior(page);
                    pause();
                    page.navigate(url, new Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                            .setTimeout(properties.navigationTimeoutMs()));
                    contentReady = waitForRealContent(page, startedAt, "RETRY2");
                } catch (Exception e) {
                    log.debug("Restaurant full retry navigation failed: {}", e.getMessage());
                }
            }

            // === STEP 4: Human-like behavior before extraction ===
            performHumanBehavior(page);
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // === STEP 5: Extract HTML ===
            String html = page.content();
            String title = page.title();
            int htmlChars = html == null ? 0 : html.length();
            int htmlBytes = byteSize(html);
            String htmlSha256 = sha256(html);
            int restaurantReviewOccurrences = countOccurrences(html, RESTAURANT_LINK_MARKER);
            String htmlSnapshotPath = saveHtmlSnapshot(html);
            String htmlPreview = preview(html, 600);

            boolean stillBlocked = isDataDomeChallenge(html, title);

            log.info("TRIPADVISOR_RESTAURANT_FETCHED url={} title='{}' htmlChars={} htmlBytes={} htmlSha256={} restaurantReviewOccurrences={} stillBlocked={} snapshotPath={} elapsedMs={}",
                    url, title, htmlChars, htmlBytes, htmlSha256, restaurantReviewOccurrences, stillBlocked, htmlSnapshotPath, elapsedMs(startedAt));
            log.info("TRIPADVISOR_RESTAURANT_HTML_PREVIEW url={} preview={}", url, htmlPreview);

            if (stillBlocked) {
                log.error("TRIPADVISOR_RESTAURANT_BLOCKED url={} reason='DataDome challenge could not be resolved after all retries' title='{}' htmlChars={} snapshotPath={} elapsedMs={}",
                        url, title, htmlChars, htmlSnapshotPath, elapsedMs(startedAt));
                return RestaurantCrawlResult.failed(url,
                        new RuntimeException("DataDome challenge could not be resolved after retries"));
            }

            // === STEP 6: Parse and persist ===
            RestaurantListingParseResult parseResult = restaurantListingParser.parse(html, url);
            String restaurantsJson = serializeRestaurants(parseResult.restaurants());
            log.info("TRIPADVISOR_RESTAURANT_EXTRACTION_SUMMARY url={} restaurantCount={} htmlBytes={} jsonChars={} restaurantReviewOccurrences={} snapshotPath={} elapsedMs={}",
                    url, parseResult.restaurantCount(), htmlBytes, restaurantsJson.length(),
                    restaurantReviewOccurrences, htmlSnapshotPath, elapsedMs(startedAt));
            if (parseResult.restaurantCount() == 0) {
                log.warn("TRIPADVISOR_RESTAURANT_ZERO_EXTRACTION_DIAGNOSTIC url={} reason='Rendered HTML contained no parseable Restaurant_Review links' htmlBytes={} restaurantReviewOccurrences={} title='{}' snapshotPath={} htmlPreview={}",
                        url, htmlBytes, restaurantReviewOccurrences, title, htmlSnapshotPath, htmlPreview);
            }
            logRestaurantSummary(parseResult.restaurants());
            log.info("TRIPADVISOR_RESTAURANT_EXTRACTED_JSON url={} restaurantCount={} jsonBytes={} restaurantsJson={}",
                    url, parseResult.restaurantCount(), restaurantsJson.getBytes(StandardCharsets.UTF_8).length, restaurantsJson);

            int persistedRows = restaurantRepository.upsertListings(parseResult.restaurants());
            log.info("TRIPADVISOR_RESTAURANT_PERSISTED url={} extractedRestaurants={} persistedRows={} elapsedMs={}",
                    url, parseResult.restaurantCount(), persistedRows, elapsedMs(startedAt));

            log.info("TRIPADVISOR_RESTAURANT_DONE url={} extractedRestaurants={} persistedRows={} htmlBytes={} totalElapsedMs={}",
                    url, parseResult.restaurantCount(), persistedRows, htmlBytes, elapsedMs(startedAt));
            return RestaurantCrawlResult.success(url, parseResult.restaurants());
        } catch (Exception e) {
            log.error("TRIPADVISOR_RESTAURANT_FAILED url={} elapsedMs={} error={}",
                    url, elapsedMs(startedAt), e.getMessage(), e);
            return RestaurantCrawlResult.failed(url, e);
        }
    }

    /**
     * Navigates the given page to {@code url}, transparently recovering when the
     * DataDome challenge auto-reload closes the original page/tab.
     */
    private Page navigateSafely(BrowserContext context, Page page, String url, long startedAt, String phase) {
        try {
            page.navigate(url, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(properties.navigationTimeoutMs()));
            return page;
        } catch (Exception e) {
            log.warn("TRIPADVISOR_RESTAURANT_NAVIGATE_RECOVER phase={} url={} error={} — recreating page and retrying",
                    phase, url, e.getMessage());
            try {
                Page fresh = context.pages().isEmpty() ? context.newPage() : context.pages().get(0);
                fresh.setDefaultNavigationTimeout(properties.navigationTimeoutMs());
                injectStealthScripts(fresh);
                fresh.navigate(url, new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                        .setTimeout(properties.navigationTimeoutMs()));
                return fresh;
            } catch (Exception retryError) {
                log.error("TRIPADVISOR_RESTAURANT_NAVIGATE_RECOVER_FAILED phase={} url={} error={}",
                        phase, url, retryError.getMessage());
                throw retryError;
            }
        }
    }

    // ==================== DataDome Challenge Handling ====================

    private boolean waitForRealContent(Page page, long startedAt, String phase) {
        long deadline = System.currentTimeMillis() + 45_000;
        int attempt = 0;
        boolean challengeDetected = false;

        while (System.currentTimeMillis() < deadline) {
            attempt++;
            try {
                String currentHtml = page.content();
                String currentTitle = page.title();

                if (currentHtml.contains(RESTAURANT_LINK_MARKER)) {
                    log.info("TRIPADVISOR_RESTAURANT_CONTENT_FOUND phase={} attempt={} title='{}' htmlLen={}",
                            phase, attempt, currentTitle, currentHtml.length());
                    return true;
                }

                if (isDataDomeChallenge(currentHtml, currentTitle)) {
                    if (!challengeDetected) {
                        challengeDetected = true;
                        log.info("TRIPADVISOR_RESTAURANT_DATADOME_DETECTED phase={} attempt={} title='{}' htmlLen={}",
                                phase, attempt, currentTitle, currentHtml.length());
                    }
                } else if (currentHtml.length() > 15_000) {
                    log.info("TRIPADVISOR_RESTAURANT_CONTENT_MAYBE phase={} attempt={} title='{}' htmlLen={}",
                            phase, attempt, currentTitle, currentHtml.length());
                    Thread.sleep(3000);
                    currentHtml = page.content();
                    if (currentHtml.contains(RESTAURANT_LINK_MARKER)) {
                        return true;
                    }
                    if (currentHtml.length() > 15_000 && !isDataDomeChallenge(currentHtml, page.title())) {
                        log.info("TRIPADVISOR_RESTAURANT_CONTENT_ACCEPTED phase={} attempt={} htmlLen={}",
                                phase, attempt, currentHtml.length());
                        return true;
                    }
                }
            } catch (Exception e) {
                log.debug("{}: Error checking page content (page may be reloading): {}", phase, e.getMessage());
            }

            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        log.warn("TRIPADVISOR_RESTAURANT_CONTENT_TIMEOUT phase={} attempts={}", phase, attempt);
        return false;
    }

    private boolean isDataDomeChallenge(String html, String title) {
        if (html == null || html.isBlank()) {
            return true;
        }
        if (html.contains(DATADOME_MARKER)) {
            return true;
        }
        if (html.contains("var dd=") && html.contains("'cid'")) {
            return true;
        }
        if (DATADOME_CHALLENGE_TITLE.equals(title) && html.length() < 5_000) {
            return true;
        }
        if (html.contains("cf-challenge") || html.contains("cf-browser-verification")) {
            return true;
        }
        return false;
    }

    // ==================== Human Behavior Simulation ====================

    private void performHumanBehavior(Page page) {
        try {
            pause();
            int x1 = 100 + (int) (Math.random() * 800);
            int y1 = 100 + (int) (Math.random() * 400);
            page.mouse().move(x1, y1);
            Thread.sleep(300 + (long) (Math.random() * 700));

            int x2 = 200 + (int) (Math.random() * 600);
            int y2 = 200 + (int) (Math.random() * 300);
            page.mouse().move(x2, y2);
            Thread.sleep(200 + (long) (Math.random() * 500));

            for (int i = 0; i < 4; i++) {
                page.mouse().wheel(0, 200 + (int) (Math.random() * 200));
                Thread.sleep(400 + (long) (Math.random() * 800));
            }

            page.mouse().wheel(0, -300);
            Thread.sleep(300 + (long) (Math.random() * 500));

            int x3 = 300 + (int) (Math.random() * 500);
            int y3 = 150 + (int) (Math.random() * 350);
            page.mouse().move(x3, y3);
            Thread.sleep(200 + (long) (Math.random() * 400));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("Human behavior simulation error (non-fatal): {}", e.getMessage());
        }
    }

    // ==================== Stealth Script ====================

    private void injectStealthScripts(Page page) {
        page.addInitScript("""
                try {
                    Object.defineProperty(navigator, 'webdriver', {
                        get: () => false,
                        configurable: true
                    });
                } catch (e) {}
                try {
                    const originalQuery = window.navigator.permissions.query;
                    window.navigator.permissions.query = (parameters) => (
                        parameters.name === 'notifications' ?
                            Promise.resolve({ state: Notification.permission }) :
                            originalQuery(parameters)
                    );
                } catch (e) {}
                delete window.callPhantom;
                delete window._phantom;
                delete window.__phantomas;
                delete window.__nightmare;
                delete window._selenium;
                delete window.__webdriver_evaluate;
                delete window.__driver_unwrapped;
                delete window.__webdriver_script_fn;
                delete window.__driver_evaluate;
                delete window.__selenium_evaluate;
                delete window.__selenium_unwrapped;
                delete window.__fxdriver_evaluate;
                delete window.__fxdriver_unwrapped;
                try {
                    const nativeToString = Function.prototype.toString;
                    const overrides = new WeakSet();
                    if (navigator.permissions && navigator.permissions.query) {
                        overrides.add(navigator.permissions.query);
                    }
                    Function.prototype.toString = function() {
                        if (overrides.has(this)) {
                            return 'function query() { [native code] }';
                        }
                        return nativeToString.call(this);
                    };
                } catch (e) {}
                """);
    }

    // ==================== Browser Configuration ====================

    private static List<String> minimalChromeArgs() {
        return List.of(
                "--no-sandbox",
                "--disable-setuid-sandbox",
                "--no-first-run",
                "--no-default-browser-check",
                "--disable-dev-shm-usage",
                "--password-store=basic",
                "--use-mock-keychain",
                "--start-minimized");
    }

    private static java.util.Map<String, String> browserHeaders() {
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("Accept-Language", "en-US,en;q=0.9");
        headers.put("Upgrade-Insecure-Requests", "1");
        return headers;
    }

    // ==================== Helper Methods ====================

    private void pause() {
        long min = properties.minDelayMs();
        long max = properties.maxDelayMs();
        long delay = max <= min ? min : ThreadLocalRandom.current().nextLong(min, max + 1);
        if (delay <= 0) {
            return;
        }
        try {
            log.info("Tripadvisor restaurant crawler delay: sleeping {}ms before next request", delay);
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during Tripadvisor restaurant crawler delay", e);
        }
    }

    private void logRestaurantSummary(List<RestaurantListing> restaurants) {
        if (restaurants.isEmpty()) {
            log.warn("TRIPADVISOR_RESTAURANT_SUMMARY restaurantCount=0 message=No restaurants extracted from rendered page");
            return;
        }
        for (int i = 0; i < restaurants.size(); i++) {
            RestaurantListing restaurant = restaurants.get(i);
            log.info("TRIPADVISOR_RESTAURANT_SUMMARY index={} id={} name='{}' url={}",
                    i + 1, restaurant.tripadvisorId(), restaurant.name(), restaurant.url());
        }
    }

    private String serializeRestaurants(List<RestaurantListing> restaurants) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(restaurants);
        } catch (Exception e) {
            log.warn("Failed to serialize Tripadvisor extracted restaurants to JSON. error={}", e.getMessage());
            return "[]";
        }
    }

    private int byteSize(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private String saveHtmlSnapshot(String html) {
        try {
            Files.createDirectories(Path.of("data", "tripadvisor-debug", "restaurants"));
            Path path = Path.of("data", "tripadvisor-debug", "restaurants", "restaurants-listing.html");
            Files.writeString(path, html == null ? "" : html, StandardCharsets.UTF_8);
            return path.toString();
        } catch (Exception e) {
            log.warn("Failed to save Tripadvisor restaurant HTML snapshot. error={}", e.getMessage());
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

    private Path ensureUserDataDir() {
        try {
            Path dir = Path.of("data", "tripadvisor-browser-profile");
            Files.createDirectories(dir);
            return dir;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create persistent browser profile directory", e);
        }
    }

    private static String resolveChromeExecutable() {
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
        log.warn("No system Google Chrome found at common paths. Falling back to Playwright's bundled Chromium (may trigger DataDome).");
        return null;
    }
}