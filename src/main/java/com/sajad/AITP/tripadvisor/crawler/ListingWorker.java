package com.sajad.AITP.tripadvisor.crawler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import com.microsoft.playwright.*;

import java.util.*;

@Slf4j
@Component
@ConditionalOnProperty(name = "tripadvisor.crawler.enabled", havingValue = "true")
public class ListingWorker {

    private static final String TRIPADVISOR_HOMEPAGE = "https://www.tripadvisor.com/";
    private static final String HOTEL_LINK_MARKER = "Hotel_Review-";
    private static final String DATADOME_MARKER = "captcha-delivery.com";
    private static final String DATADOME_CHALLENGE_TITLE = "tripadvisor.com";

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

        Path userDataDir = ensureUserDataDir();
        String chromePath = resolveChromeExecutable();
        logProgress("BROWSER_OPEN", crawlPage, startedAt,
                "Opening system Google Chrome with persistent profile. headless=%s timeoutMs=%d chromePath=%s userDataDir=%s"
                        .formatted(properties.headless(), properties.navigationTimeoutMs(), chromePath, userDataDir));

        // Minimal launch args — real Chrome doesn't launch with 20 flags
        java.util.List<String> launchArgs = new java.util.ArrayList<>(minimalChromeArgs());
        if (properties.headless()) {
            launchArgs.add("");
        }

        BrowserType.LaunchPersistentContextOptions launchOptions =
                new BrowserType.LaunchPersistentContextOptions()
                        .setHeadless(false) // never use old headless; we control via args
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
             BrowserContext context = playwright.chromium().launchPersistentContext(
                     userDataDir, launchOptions)) {

            Page page = context.pages().isEmpty() ? context.newPage() : context.pages().get(0);
            page.setDefaultNavigationTimeout(properties.navigationTimeoutMs());
            injectStealthScripts(page);

            // === STEP 1: Homepage warmup — establish DataDome cookies ===
            // The first request to any DataDome-protected page always gets challenged.
            // By visiting the homepage first, we let the challenge auto-resolve and
            // obtain the datadome cookie, which is then sent on subsequent requests.
            logProgress("WARMUP_START", crawlPage, startedAt,
                    "Visiting Tripadvisor homepage for DataDome cookie warming");
            page = navigateSafely(context, page, TRIPADVISOR_HOMEPAGE, crawlPage, startedAt, "WARMUP");

            boolean warmupOk = waitForRealContent(page, crawlPage, startedAt, "WARMUP");
            if (warmupOk) {
                // Check if datadome cookie was set
                boolean hasDataDomeCookie = context.cookies().stream()
                        .anyMatch(cookie -> "datadome".equals(cookie.name));
                logProgress("WARMUP_DONE", crawlPage, startedAt,
                        "Homepage loaded. datadomeCookiePresent=%b".formatted(hasDataDomeCookie));
                performHumanBehavior(page);
            } else {
                logProgress("WARMUP_WARNING", crawlPage, startedAt,
                        "Homepage warmup may not have fully resolved, proceeding anyway");
            }
            randomDelay.pause();

            // === STEP 2: Navigate to the target listing page ===
            logProgress("NAVIGATE_START", crawlPage, startedAt, "Navigating to Tripadvisor listing page");
            page = navigateSafely(context, page, crawlPage.url(), crawlPage, startedAt, "LISTING");

            // === STEP 3: Wait for DataDome challenge to resolve and real content to appear ===
            // DataDome serves a JS challenge page that auto-fingerprints the browser,
            // submits results, receives a cookie, and reloads the page. We must WAIT
            // for this process to complete before extracting HTML.
            logProgress("WAIT_CONTENT", crawlPage, startedAt,
                    "Waiting for page content (handling DataDome challenge if present)");
            boolean contentReady = waitForRealContent(page, crawlPage, startedAt, "LISTING");

            // === STEP 3b: Retry with reload if blocked ===
            if (!contentReady) {
                logProgress("RETRY_RELOAD", crawlPage, startedAt,
                        "Content not found, reloading page (retry 1)");
                randomDelay.pause();
                try {
                    page.reload(new Page.ReloadOptions()
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                            .setTimeout(properties.navigationTimeoutMs()));
                } catch (Exception e) {
                    log.debug("Reload failed: {}", e.getMessage());
                }
                contentReady = waitForRealContent(page, crawlPage, startedAt, "RETRY1");
            }

            // === STEP 3c: Full retry — re-warmup then re-navigate ===
            if (!contentReady) {
                logProgress("RETRY_FULL", crawlPage, startedAt,
                        "Content still not found, doing full re-warmup + re-navigate (retry 2)");
                randomDelay.pause();
                try {
                    page.navigate(TRIPADVISOR_HOMEPAGE, new Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                            .setTimeout(properties.navigationTimeoutMs()));
                    waitForRealContent(page, crawlPage, startedAt, "RETRY_WARMUP");
                    performHumanBehavior(page);
                    randomDelay.pause();
                    page.navigate(crawlPage.url(), new Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                            .setTimeout(properties.navigationTimeoutMs()));
                    contentReady = waitForRealContent(page, crawlPage, startedAt, "RETRY2");
                } catch (Exception e) {
                    log.debug("Full retry navigation failed: {}", e.getMessage());
                }
            }

            // === STEP 4: Human-like behavior before extraction ===
            performHumanBehavior(page);

            // Give SPA a moment to render hotel cards via JavaScript
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
            int hotelReviewOccurrences = countOccurrences(html, HOTEL_LINK_MARKER);
            int captchaOccurrences = countOccurrencesIgnoreCase(html, "captcha");
            int accessDeniedOccurrences = countOccurrencesIgnoreCase(html, "access denied");
            int securityCheckOccurrences = countOccurrencesIgnoreCase(html, "security check");
            int botOccurrences = countOccurrencesIgnoreCase(html, "bot");
            String htmlSnapshotPath = saveHtmlSnapshot(crawlPage, html);
            String htmlPreview = preview(html, 600);

            boolean stillBlocked = isDataDomeChallenge(html, title);

            log.info("TRIPADVISOR_FETCHED_DATA url={} offset={} title='{}' htmlChars={} htmlBytes={} htmlSha256={} hotelReviewOccurrences={} captchaOccurrences={} accessDeniedOccurrences={} securityCheckOccurrences={} botOccurrences={} stillBlocked={} snapshotPath={} elapsedMs={}",
                    crawlPage.url(), crawlPage.offset(), title, htmlChars, htmlBytes, htmlSha256, hotelReviewOccurrences,
                    captchaOccurrences, accessDeniedOccurrences, securityCheckOccurrences, botOccurrences, stillBlocked, htmlSnapshotPath, elapsedMs(startedAt));
            log.info("TRIPADVISOR_HTML_PREVIEW url={} offset={} preview={}", crawlPage.url(), crawlPage.offset(), htmlPreview);

            // If still blocked after all retries, fail with a clear error
            if (stillBlocked) {
                log.error("TRIPADVISOR_BLOCKED url={} offset={} reason='DataDome challenge could not be resolved after all retries. Consider: (1) running headed mode, (2) using residential proxy, (3) increasing delays.' elapsedMs={}",
                        crawlPage.url(), crawlPage.offset(), elapsedMs(startedAt));
                if (!properties.singlePageOnly()) {
                    pageRepository.markFailed(crawlPage.offset(), crawlPage.url(),
                            new RuntimeException("DataDome challenge could not be resolved"));
                }
                return ListingCrawlResult.failed(crawlPage,
                        new RuntimeException("DataDome challenge could not be resolved after retries"));
            }

            // === STEP 6: Parse and persist ===
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
            return ListingCrawlResult.success(crawlPage, parseResult.hotelCount(), parseResult.hotels());
        } catch (Exception e) {
            if (!properties.singlePageOnly()) {
                pageRepository.markFailed(crawlPage.offset(), crawlPage.url(), e);
            }
            log.error("TRIPADVISOR_FAILED url={} offset={} elapsedMs={} error={}",
                    crawlPage.url(), crawlPage.offset(), elapsedMs(startedAt), e.getMessage(), e);
            return ListingCrawlResult.failed(crawlPage, e);
        }
    }

    /**
     * Navigates the given page to {@code url}, transparently recovering when the
     * DataDome challenge auto-reload closes the original page/tab. If the page is
     * no longer usable, a fresh page is created from the context and the
     * navigation is retried on it. Returns the usable page (possibly a new one).
     */
    private Page navigateSafely(BrowserContext context, Page page, String url,
                                CrawlPage crawlPage, long startedAt, String phase) {
        try {
            page.navigate(url, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(properties.navigationTimeoutMs()));
            return page;
        } catch (Exception e) {
            log.warn("TRIPADVISOR_NAVIGATE_RECOVER {} phase={} url={} error={} — recreating page and retrying",
                    phase, phase, url, e.getMessage());
            try {
                Page fresh = context.pages().isEmpty() ? context.newPage() : context.pages().get(0);
                fresh.setDefaultNavigationTimeout(properties.navigationTimeoutMs());
                injectStealthScripts(fresh);
                fresh.navigate(url, new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                        .setTimeout(properties.navigationTimeoutMs()));
                return fresh;
            } catch (Exception retryError) {
                log.error("TRIPADVISOR_NAVIGATE_RECOVER_FAILED phase={} url={} error={}",
                        phase, url, retryError.getMessage());
                throw retryError;
            }
        }
    }

    // ==================== DataDome Challenge Handling ====================

    /**
     * Polls the page until real content (hotel links) appears or the DataDome
     * challenge auto-resolves. DataDome's challenge page contains JavaScript that:
     * 1. Fingerprints the browser (canvas, WebGL, etc.)
     * 2. POSTs results to geo.captcha-delivery.com
     * 3. Receives a datadome cookie
     * 4. Auto-reloads the page with real content
     * <p>
     * This process takes 3-10 seconds. The previous code grabbed HTML immediately
     * after DOMContentLoaded, before the challenge had time to resolve.
     */
    private boolean waitForRealContent(Page page, CrawlPage crawlPage, long startedAt, String phase) {
        long deadline = System.currentTimeMillis() + 45_000; // 45 second timeout
        int attempt = 0;
        boolean challengeDetected = false;

        while (System.currentTimeMillis() < deadline) {
            attempt++;
            try {
                String currentHtml = page.content();
                String currentTitle = page.title();

                // Success: hotel links found in the rendered HTML
                if (currentHtml.contains(HOTEL_LINK_MARKER)) {
                    logProgress("CONTENT_FOUND", crawlPage, startedAt,
                            "%s: Real page content with hotel links detected. attempt=%d title='%s' htmlLen=%d".formatted(
                                    phase, attempt, currentTitle, currentHtml.length()));
                    return true;
                }

                // Detect DataDome challenge page
                if (isDataDomeChallenge(currentHtml, currentTitle)) {
                    if (!challengeDetected) {
                        challengeDetected = true;
                        logProgress("DATADOME_DETECTED", crawlPage, startedAt,
                                "%s: DataDome challenge page detected, waiting for auto-resolution... attempt=%d title='%s' htmlLen=%d".formatted(
                                        phase, attempt, currentTitle, currentHtml.length()));
                    }
                    // The challenge script will auto-reload the page.
                    // Just wait and poll again.
                } else if (currentHtml.length() > 15_000) {
                    // Substantial content but no hotel links — might be a different page type
                    // or the SPA is still rendering. Wait a bit more.
                    logProgress("CONTENT_MAYBE", crawlPage, startedAt,
                            "%s: Substantial content but no hotel links yet, waiting for JS render. attempt=%d title='%s' htmlLen=%d".formatted(
                                    phase, attempt, currentTitle, currentHtml.length()));
                    Thread.sleep(3000);
                    // Check again after waiting
                    currentHtml = page.content();
                    if (currentHtml.contains(HOTEL_LINK_MARKER)) {
                        return true;
                    }
                    // If still no hotel links but content is substantial and not a challenge,
                    // return true (might be end of listings or different page structure)
                    if (currentHtml.length() > 15_000 && !isDataDomeChallenge(currentHtml, page.title())) {
                        logProgress("CONTENT_ACCEPTED", crawlPage, startedAt,
                                "%s: Accepting page with substantial content (no hotel links found but not a challenge page). attempt=%d htmlLen=%d".formatted(
                                        phase, attempt, currentHtml.length()));
                        return true;
                    }
                }
            } catch (Exception e) {
                // Page might be in the middle of a reload (challenge auto-resolve)
                log.debug("{}: Error checking page content (page may be reloading): {}", phase, e.getMessage());
            }

            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        logProgress("CONTENT_TIMEOUT", crawlPage, startedAt,
                "%s: Timed out waiting for real content after %d attempts".formatted(phase, attempt));
        return false;
    }

    /**
     * Detects whether the current page is a DataDome challenge page.
     * <p>
     * DataDome challenge pages have these characteristics:
     * - Title is "tripadvisor.com" (lowercase — real pages have descriptive titles)
     * - Very small HTML (< 5KB — real pages are 100KB+)
     * - Contains references to "captcha-delivery.com"
     * - Contains a JavaScript variable "dd" with challenge parameters
     */
    private boolean isDataDomeChallenge(String html, String title) {
        if (html == null || html.isBlank()) {
            return true;
        }
        // DataDome's script host
        if (html.contains(DATADOME_MARKER)) {
            return true;
        }
        // DataDome challenge variable
        if (html.contains("var dd=") && html.contains("'cid'")) {
            return true;
        }
        // Challenge page title + small HTML
        if (DATADOME_CHALLENGE_TITLE.equals(title) && html.length() < 5_000) {
            return true;
        }
        // Explicit security check pages
        if (html.contains("cf-challenge") || html.contains("cf-browser-verification")) {
            return true;
        }
        return false;
    }

    // ==================== Human Behavior Simulation ====================

    /**
     * Simulates human-like browser interactions to reduce bot detection risk.
     * DataDome tracks mouse movements, scroll patterns, and interaction timing.
     * <p>
     * Key behaviors:
     * - Random mouse movements (not instant teleport)
     * - Gradual scrolling (simulates reading)
     * - Variable timing between actions (not perfectly periodic)
     */
    private void performHumanBehavior(Page page) {
        try {
            randomDelay.pause();

            // Random mouse movement to a position
            int x1 = 100 + (int) (Math.random() * 800);
            int y1 = 100 + (int) (Math.random() * 400);
            page.mouse().move(x1, y1);
            Thread.sleep(300 + (long) (Math.random() * 700));

            // Move mouse again (humans move multiple times)
            int x2 = 200 + (int) (Math.random() * 600);
            int y2 = 200 + (int) (Math.random() * 300);
            page.mouse().move(x2, y2);
            Thread.sleep(200 + (long) (Math.random() * 500));

            // Scroll down gradually (simulates reading the page)
            for (int i = 0; i < 4; i++) {
                page.mouse().wheel(0, 200 + (int) (Math.random() * 200));
                Thread.sleep(400 + (long) (Math.random() * 800));
            }

            // Scroll back up slightly (humans re-read sections)
            page.mouse().wheel(0, -300);
            Thread.sleep(300 + (long) (Math.random() * 500));

            // Final random mouse movement
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

    /**
     * Injects MINIMAL stealth scripts to hide Playwright automation markers.
     * <p>
     * CRITICAL: Less is more. The previous script overrode too many properties,
     * and the overrides themselves were detectable:
     * - navigator.webdriver = undefined → real Chrome has false, not undefined
     * - window.chrome = { runtime: {} } → real Chrome has a much richer object
     * - Fake plugins array → outdated, doesn't match modern Chrome
     * - WebGL vendor "Intel Iris OpenGL Engine" on Linux → suspicious
     * - Screen dimension overrides → detectable via property descriptor checks
     * <p>
     * We now only fix the most critical, universally-checked signals:
     * 1. navigator.webdriver (Playwright sets true, real Chrome has false)
     * 2. permissions.query (Playwright breaks this)
     * 3. Remove automation framework traces
     * 4. Hide toString() tampering
     */
    private void injectStealthScripts(Page page) {
        page.addInitScript("""
                // --- Fix navigator.webdriver ---
                // Playwright sets this to true. Real Chrome has false.
                // Do NOT set to undefined — that's itself a detection signal.
                try {
                    Object.defineProperty(navigator, 'webdriver', {
                        get: () => false,
                        configurable: true
                    });
                } catch (e) {}
                
                // --- Fix permissions.query ---
                // Playwright breaks the native permissions API
                try {
                    const originalQuery = window.navigator.permissions.query;
                    window.navigator.permissions.query = (parameters) => (
                        parameters.name === 'notifications' ?
                            Promise.resolve({ state: Notification.permission }) :
                            originalQuery(parameters)
                    );
                } catch (e) {}
                
                // --- Remove automation framework traces ---
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
                
                // --- Hide toString() tampering ---
                // Bot detectors call toString() on native functions to check
                // if they've been monkey-patched. We patch toString itself.
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

    /**
     * Minimal Chrome launch arguments.
     * <p>
     * The previous code had 20+ flags (--disable-extensions, --mute-audio,
     * --disable-sync, --disable-popup-blocking, etc.). Real Chrome users
     * don't launch with 20 command-line flags. While DataDome can't directly
     * read launch flags, some flags affect detectable browser behavior.
     * <p>
     * We keep only essential flags for Docker/Linux compatibility.
     */
    private static java.util.List<String> minimalChromeArgs() {
        return java.util.List.of(
                "--no-sandbox",                    // Required for Docker/root
                "--disable-setuid-sandbox",        // Required for Docker/root
                "--no-first-run",                  // Skip first-run wizard
                "--no-default-browser-check",      // Skip default browser prompt
                "--disable-dev-shm-usage",         // Prevent /dev/shm issues in Docker
                "--password-store=basic",          // Avoid keyring issues on Linux
                "--use-mock-keychain"              // Avoid keychain issues on Linux
        );
    }

    /**
     * HTTP headers sent with every request.
     * <p>
     * We keep this MINIMAL — Chrome automatically sends most headers
     * (sec-ch-ua, sec-fetch-*, Accept-Encoding, etc.) with correct values
     * matching the actual Chrome version. Setting them manually risks
     * creating a mismatch between the sec-ch-ua header and the real version.
     */
    private static java.util.Map<String, String> browserHeaders() {
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("Accept-Language", "en-US,en;q=0.9");
        headers.put("Upgrade-Insecure-Requests", "1");
        return headers;
    }

    // ==================== Helper Methods (unchanged) ====================

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