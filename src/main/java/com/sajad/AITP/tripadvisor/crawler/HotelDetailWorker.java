package com.sajad.AITP.tripadvisor.crawler;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitUntilState;
import com.sajad.AITP.tripadvisor.config.TripadvisorCrawlerProperties;
import com.sajad.AITP.tripadvisor.model.HotelDetail;
import com.sajad.AITP.tripadvisor.model.HotelDetailCrawlResult;
import com.sajad.AITP.tripadvisor.model.HotelListing;
import com.sajad.AITP.tripadvisor.parser.HotelDetailParser;
import com.sajad.AITP.tripadvisor.repository.HotelRepository;
import com.sajad.AITP.tripadvisor.util.RandomDelay;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Stage 2 worker: opens each individual Tripadvisor hotel review URL and
 * extracts detailed data (name, address, geo, rating, reviews, price range,
 * phone, description) which is then mapped onto the shared {@code poi} model
 * and persisted.
 * <p>
 * Each invocation opens its own Playwright/browser instance (Playwright objects
 * are not thread-safe) and reuses the same DataDome warm-up + human-behavior
 * strategy as {@link ListingWorker}.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "tripadvisor.crawler.enabled", havingValue = "true")
public class HotelDetailWorker {

    private static final String TRIPADVISOR_HOMEPAGE = "https://www.tripadvisor.com/";
    private static final String DATADOME_MARKER = "captcha-delivery.com";
    private static final String DATADOME_CHALLENGE_TITLE = "tripadvisor.com";

    private final TripadvisorCrawlerProperties properties;
    private final HotelDetailParser hotelDetailParser;
    private final HotelRepository hotelRepository;
    private final RandomDelay randomDelay;

    public HotelDetailWorker(
            TripadvisorCrawlerProperties properties,
            HotelDetailParser hotelDetailParser,
            HotelRepository hotelRepository,
            RandomDelay randomDelay) {
        this.properties = properties;
        this.hotelDetailParser = hotelDetailParser;
        this.hotelRepository = hotelRepository;
        this.randomDelay = randomDelay;
    }

    /**
     * Crawls a single hotel detail page and persists the mapped POI.
     *
     * @return a {@link HotelDetailCrawlResult} carrying the parsed detail on
     *         success and a human-readable reason on failure.
     */
    public HotelDetailCrawlResult crawl(HotelListing listing) {
        long startedAt = System.currentTimeMillis();
        log.info("TRIPADVISOR_HOTEL_DETAIL_START tripadvisorId={} url={} sourceListingUrl={}",
                listing.tripadvisorId(), listing.url(), listing.sourceListingUrl());

        Path userDataDir = ensureUserDataDir();
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

            // Warm up homepage to establish DataDome cookies
            log.info("TRIPADVISOR_HOTEL_DETAIL_WARMUP tripadvisorId={} url={} message=Visiting homepage for DataDome cookie warming",
                    listing.tripadvisorId(), listing.url());
            page.navigate(TRIPADVISOR_HOMEPAGE, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(properties.navigationTimeoutMs()));
            boolean warmupOk = waitForRealContent(page, listing, startedAt, "WARMUP");
            if (warmupOk) {
                performHumanBehavior(page);
            } else {
                log.warn("TRIPADVISOR_HOTEL_DETAIL_WARMUP_WARNING tripadvisorId={} url={} message=Homepage warmup may not have fully resolved",
                        listing.tripadvisorId(), listing.url());
            }
            randomDelay.pause();

            // Navigate to the hotel detail page
            log.info("TRIPADVISOR_HOTEL_DETAIL_NAVIGATE tripadvisorId={} url={} message=Navigating to hotel detail page",
                    listing.tripadvisorId(), listing.url());
            page.navigate(listing.url(), new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(properties.navigationTimeoutMs()));

            boolean contentReady = waitForRealContent(page, listing, startedAt, "HOTEL_DETAIL");

            // Retry with reload if blocked
            if (!contentReady) {
                log.warn("TRIPADVISOR_HOTEL_DETAIL_RETRY_RELOAD tripadvisorId={} url={} message=Content not found, reloading page (retry 1)",
                        listing.tripadvisorId(), listing.url());
                randomDelay.pause();
                try {
                    page.reload(new Page.ReloadOptions()
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                            .setTimeout(properties.navigationTimeoutMs()));
                } catch (Exception e) {
                    log.debug("Reload failed: {}", e.getMessage());
                }
                contentReady = waitForRealContent(page, listing, startedAt, "RETRY1");
            }

            // Full retry — re-warmup then re-navigate
            if (!contentReady) {
                log.warn("TRIPADVISOR_HOTEL_DETAIL_RETRY_FULL tripadvisorId={} url={} message=Content still not found, doing full re-warmup + re-navigate (retry 2)",
                        listing.tripadvisorId(), listing.url());
                randomDelay.pause();
                try {
                    page.navigate(TRIPADVISOR_HOMEPAGE, new Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                            .setTimeout(properties.navigationTimeoutMs()));
                    waitForRealContent(page, listing, startedAt, "RETRY_WARMUP");
                    performHumanBehavior(page);
                    randomDelay.pause();
                    page.navigate(listing.url(), new Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                            .setTimeout(properties.navigationTimeoutMs()));
                    contentReady = waitForRealContent(page, listing, startedAt, "RETRY2");
                } catch (Exception e) {
                    log.debug("Full retry navigation failed: {}", e.getMessage());
                }
            }

            performHumanBehavior(page);

            // Give SPA a moment to render
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            String html = page.content();
            String title = page.title();
            boolean stillBlocked = isDataDomeChallenge(html, title);
            String snapshotPath = saveHtmlSnapshot(listing, html);

            log.info("TRIPADVISOR_HOTEL_DETAIL_FETCHED tripadvisorId={} url={} title='{}' htmlChars={} htmlBytes={} stillBlocked={} snapshotPath={} elapsedMs={}",
                    listing.tripadvisorId(), listing.url(), title, html == null ? 0 : html.length(),
                    byteSize(html), stillBlocked, snapshotPath, elapsedMs(startedAt));

            if (stillBlocked) {
                log.error("TRIPADVISOR_HOTEL_DETAIL_BLOCKED tripadvisorId={} url={} reason='DataDome challenge could not be resolved after all retries' title='{}' htmlChars={} snapshotPath={} elapsedMs={}",
                        listing.tripadvisorId(), listing.url(), title, html == null ? 0 : html.length(), snapshotPath, elapsedMs(startedAt));
                return HotelDetailCrawlResult.failure("DataDome challenge could not be resolved after all retries");
            }

            HotelDetail detail = hotelDetailParser.parse(html, listing.url(), listing.sourceListingUrl());
            log.info("TRIPADVISOR_HOTEL_DETAIL_EXTRACTED tripadvisorId={} url={} name='{}' lat={} lon={} rating={} reviewCount={} elapsedMs={}",
                    detail.tripadvisorId(), detail.url(), detail.name(), detail.latitude(), detail.longitude(),
                    detail.rating(), detail.reviewCount(), elapsedMs(startedAt));

            int persistedRows = hotelRepository.upsertHotelDetail(detail);
            log.info("TRIPADVISOR_HOTEL_DETAIL_PERSISTED tripadvisorId={} url={} affectedRows={} elapsedMs={}",
                    detail.tripadvisorId(), detail.url(), persistedRows, elapsedMs(startedAt));

            log.info("TRIPADVISOR_HOTEL_DETAIL_DONE tripadvisorId={} url={} totalElapsedMs={}",
                    detail.tripadvisorId(), detail.url(), elapsedMs(startedAt));
            return HotelDetailCrawlResult.success(detail);
        } catch (Exception e) {
            log.error("TRIPADVISOR_HOTEL_DETAIL_FAILED tripadvisorId={} url={} elapsedMs={} errorType={} error={}",
                    listing.tripadvisorId(), listing.url(), elapsedMs(startedAt), e.getClass().getSimpleName(), e.getMessage(), e);
            return HotelDetailCrawlResult.failure(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ==================== DataDome handling ====================

    private boolean waitForRealContent(Page page, HotelListing listing, long startedAt, String phase) {
        long deadline = System.currentTimeMillis() + 45_000;
        int attempt = 0;
        boolean challengeDetected = false;

        while (System.currentTimeMillis() < deadline) {
            attempt++;
            try {
                String currentHtml = page.content();
                String currentTitle = page.title();

                // Success: hotel detail content present (title is descriptive, not the challenge title)
                if (currentHtml.length() > 15_000 && !isDataDomeChallenge(currentHtml, currentTitle)) {
                    log.info("TRIPADVISOR_HOTEL_DETAIL_CONTENT_FOUND tripadvisorId={} url={} phase={} attempt={} title='{}' htmlLen={}",
                            listing.tripadvisorId(), listing.url(), phase, attempt, currentTitle, currentHtml.length());
                    return true;
                }

                if (isDataDomeChallenge(currentHtml, currentTitle)) {
                    if (!challengeDetected) {
                        challengeDetected = true;
                        log.info("TRIPADVISOR_HOTEL_DETAIL_DATADOME_DETECTED tripadvisorId={} url={} phase={} attempt={} title='{}' htmlLen={}",
                                listing.tripadvisorId(), listing.url(), phase, attempt, currentTitle, currentHtml.length());
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

        log.warn("TRIPADVISOR_HOTEL_DETAIL_CONTENT_TIMEOUT tripadvisorId={} url={} phase={} attempts={}",
                listing.tripadvisorId(), listing.url(), phase, attempt);
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

    // ==================== Human behavior ====================

    private void performHumanBehavior(Page page) {
        try {
            randomDelay.pause();
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

    // ==================== Stealth ====================

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

    // ==================== Browser config ====================

    private static List<String> minimalChromeArgs() {
        return List.of(
                "--no-sandbox",
                "--disable-setuid-sandbox",
                "--no-first-run",
                "--no-default-browser-check",
                "--disable-dev-shm-usage",
                "--password-store=basic",
                "--use-mock-keychain");
    }

    private static java.util.Map<String, String> browserHeaders() {
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("Accept-Language", "en-US,en;q=0.9");
        headers.put("Upgrade-Insecure-Requests", "1");
        return headers;
    }

    // ==================== Helpers ====================

    private int byteSize(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private String saveHtmlSnapshot(HotelListing listing, String html) {
        try {
            Files.createDirectories(Path.of("data", "tripadvisor-debug", "hotels"));
            Path path = Path.of("data", "tripadvisor-debug", "hotels",
                    "hotel-" + listing.tripadvisorId() + ".html");
            Files.writeString(path, html == null ? "" : html, StandardCharsets.UTF_8);
            return path.toString();
        } catch (Exception e) {
            log.warn("Failed to save Tripadvisor hotel detail HTML snapshot. tripadvisorId={}, error={}",
                    listing.tripadvisorId(), e.getMessage());
            return "<not-saved>";
        }
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
