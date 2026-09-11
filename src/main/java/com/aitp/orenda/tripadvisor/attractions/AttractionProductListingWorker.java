package com.aitp.orenda.tripadvisor.attractions;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitUntilState;
import com.aitp.orenda.tripadvisor.util.DiskSpaceGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Stage 1 worker for the attraction products crawler: opens the Tripadvisor
 * {@code Attraction_Products} listing URL, waits out any DataDome challenge,
 * then repeatedly clicks the "See More" button until the full lazy-loaded list
 * is rendered (~110 dinner cruise products), extracts the rendered HTML and
 * hands it to {@link AttractionProductListingParser}.
 * <p>
 * Unlike hotels/restaurants this listing page has no {@code oa} pagination —
 * all items live on one page and are revealed by clicking "See More".
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "tripadvisor.crawler.attraction-products.enabled", havingValue = "true")
public class AttractionProductListingWorker {

    private static final String TRIPADVISOR_HOMEPAGE = "https://www.tripadvisor.com/";
    private static final String PRODUCT_LINK_MARKER = "AttractionProductReview-";
    private static final String DATADOME_MARKER = "captcha-delivery.com";
    private static final String DATADOME_CHALLENGE_TITLE = "tripadvisor.com";

    private final AttractionProductCrawlerProperties properties;
    private final AttractionProductRepository productRepository;
    private final DiskSpaceGuard diskSpaceGuard;

    public AttractionProductListingWorker(
            AttractionProductCrawlerProperties properties,
            AttractionProductRepository productRepository,
            DiskSpaceGuard diskSpaceGuard) {
        this.properties = properties;
        this.productRepository = productRepository;
        this.diskSpaceGuard = diskSpaceGuard;
    }

    public AttractionProductCrawlResult crawl(String url) {
        long startedAt = System.currentTimeMillis();
        log.info("TRIPADVISOR_ATTRACTION_PRODUCT_START url={} headless={} navigationTimeoutMs={} maxItems={} seeMoreMaxClicks={}",
                url, properties.headless(), properties.navigationTimeoutMs(), properties.maxItems(), properties.seeMoreMaxClicks());

        Path userDataDir = ensureUserDataDir();
        if (!diskSpaceGuard.hasEnoughSpace(userDataDir)) {
            log.error("TRIPADVISOR_ATTRACTION_PRODUCT_DISK_FULL url={} message=Free disk space {} bytes is below minimum {} bytes. Skipping page.",
                    url, diskSpaceGuard.freeBytes(userDataDir), diskSpaceGuard.minFreeBytes());
            return AttractionProductCrawlResult.failed(url,
                    new RuntimeException("Disk space too low to launch browser; free at least "
                            + diskSpaceGuard.minFreeBytes() + " bytes"));
        }
        // Firefox (native user agent) passes DataDome where Chromium is persistently
        // challenged. Keep the default Firefox UA — the browser is fully self-consistent.
        BrowserType.LaunchPersistentContextOptions launchOptions =
                new BrowserType.LaunchPersistentContextOptions()
                        .setHeadless(false)
                        .setLocale("en-US")
                        .setTimezoneId("Europe/Istanbul")
                        .setViewportSize(1366, 768)
                        .setExtraHTTPHeaders(browserHeaders());

        try (Playwright playwright = Playwright.create();
             BrowserContext context = playwright.firefox().launchPersistentContext(userDataDir, launchOptions)) {

            Page page = context.pages().isEmpty() ? context.newPage() : context.pages().get(0);
            page.setDefaultNavigationTimeout(properties.navigationTimeoutMs());
            injectStealthScripts(page);

            // === STEP 1: Homepage warmup — establish DataDome cookies ===
            log.info("TRIPADVISOR_ATTRACTION_PRODUCT_WARMUP url={} message=Visiting Tripadvisor homepage for DataDome cookie warming", url);
            page = navigateSafely(context, page, TRIPADVISOR_HOMEPAGE, "WARMUP");
            boolean warmupOk = waitForRealContent(page, "WARMUP");
            if (warmupOk) {
                boolean hasDataDomeCookie = context.cookies().stream()
                        .anyMatch(cookie -> "datadome".equals(cookie.name));
                log.info("TRIPADVISOR_ATTRACTION_PRODUCT_WARMUP_DONE url={} datadomeCookiePresent={}", url, hasDataDomeCookie);
                performHumanBehavior(page);
            } else {
                log.warn("TRIPADVISOR_ATTRACTION_PRODUCT_WARMUP_WARNING url={} message=Homepage warmup may not have fully resolved", url);
            }
            pause();

            // === STEP 2: Navigate to the listing page ===
            log.info("TRIPADVISOR_ATTRACTION_PRODUCT_NAVIGATE url={} message=Navigating to attraction products listing page", url);
            page = navigateSafely(context, page, url, "LISTING");

            // === STEP 3: Wait for DataDome challenge to resolve and real content to appear ===
            boolean contentReady = waitForRealContent(page, "LISTING");

            // === STEP 3b: Retry with reload if blocked ===
            if (!contentReady) {
                log.warn("TRIPADVISOR_ATTRACTION_PRODUCT_RETRY_RELOAD url={} message=Content not found, reloading page (retry 1)", url);
                pause();
                try {
                    page.reload(new Page.ReloadOptions()
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                            .setTimeout(properties.navigationTimeoutMs()));
                } catch (Exception e) {
                    log.debug("Reload failed: {}", e.getMessage());
                }
                contentReady = waitForRealContent(page, "RETRY1");
            }

            // === STEP 3c: Full retry — re-warmup then re-navigate ===
            if (!contentReady) {
                log.warn("TRIPADVISOR_ATTRACTION_PRODUCT_RETRY_FULL url={} message=Content still not found, doing full re-warmup + re-navigate (retry 2)", url);
                pause();
                try {
                    page.navigate(TRIPADVISOR_HOMEPAGE, new Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                            .setTimeout(properties.navigationTimeoutMs()));
                    waitForRealContent(page, "RETRY_WARMUP");
                    performHumanBehavior(page);
                    pause();
                    page.navigate(url, new Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                            .setTimeout(properties.navigationTimeoutMs()));
                    contentReady = waitForRealContent(page, "RETRY2");
                } catch (Exception e) {
                    log.debug("Full retry navigation failed: {}", e.getMessage());
                }
            }

            // === STEP 4: Collect every product by revealing the lazy-loaded list ===
            // The page is an infinite/virtualized list (says "95 results" but only ~28
            // rows are in the DOM at once). "See More" loads more into state but rows
            // only render as we scroll, so we accumulate ids across a scroll+click pass
            // rather than relying on a single page.content() capture.
            List<AttractionProductListing> products = collectAllProducts(page, startedAt, url);
            log.info("TRIPADVISOR_ATTRACTION_PRODUCT_COLLECTED url={} collectedProducts={} targetMaxItems={} elapsedMs={}",
                    url, products.size(), properties.maxItems(), elapsedMs(startedAt));

            // === STEP 5: Extract HTML + diagnostics ===
            performHumanBehavior(page);
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            String html = page.content();
            String title = page.title();
            boolean stillBlocked = isDataDomeChallenge(html, title);
            String htmlSnapshotPath = saveHtmlSnapshot(html);
            int productLinkOccurrences = countOccurrences(html, PRODUCT_LINK_MARKER);

            log.info("TRIPADVISOR_ATTRACTION_PRODUCT_FETCHED url={} title='{}' htmlChars={} htmlBytes={} productLinkOccurrences={} productCount={} stillBlocked={} snapshotPath={} elapsedMs={}",
                    url, title, html == null ? 0 : html.length(), byteSize(html), productLinkOccurrences,
                    products.size(), stillBlocked, htmlSnapshotPath, elapsedMs(startedAt));

            if (stillBlocked) {
                log.error("TRIPADVISOR_ATTRACTION_PRODUCT_BLOCKED url={} reason='DataDome challenge could not be resolved after all retries' title='{}' snapshotPath={} elapsedMs={}",
                        url, title, htmlSnapshotPath, elapsedMs(startedAt));
                return AttractionProductCrawlResult.failed(url,
                        new RuntimeException("DataDome challenge could not be resolved after retries"));
            }

            // === STEP 6: Persist ===
            if (products.isEmpty()) {
                log.warn("TRIPADVISOR_ATTRACTION_PRODUCT_ZERO_EXTRACTION_DIAGNOSTIC url={} reason='No parseable AttractionProductReview links collected' htmlBytes={} productLinkOccurrences={} title='{}' snapshotPath={}",
                        url, byteSize(html), productLinkOccurrences, title, htmlSnapshotPath);
            }
            logProductSummary(products);

            String categoryName = resolveCategoryName(page);
            log.info("TRIPADVISOR_ATTRACTION_PRODUCT_CATEGORY url={} resolvedCategory='{}'", url, categoryName);

            int persistedRows = productRepository.upsertListings(products, categoryName);
            log.info("TRIPADVISOR_ATTRACTION_PRODUCT_PERSISTED url={} extractedProducts={} persistedRows={} category='{}' elapsedMs={}",
                    url, products.size(), persistedRows, categoryName, elapsedMs(startedAt));

            log.info("TRIPADVISOR_ATTRACTION_PRODUCT_DONE url={} extractedProducts={} persistedRows={} totalElapsedMs={}",
                    url, products.size(), persistedRows, elapsedMs(startedAt));
            return AttractionProductCrawlResult.success(url, products, categoryName);
        } catch (Exception e) {
            log.error("TRIPADVISOR_ATTRACTION_PRODUCT_FAILED url={} elapsedMs={} error={}",
                    url, elapsedMs(startedAt), e.getMessage(), e);
            return AttractionProductCrawlResult.failed(url, e);
        }
    }

    /**
     * Resolves the listing's theme/category from the page heading — the bold
     * {@code <h1>} at the top of the listing page, e.g.
     * {@code "Cultural & Theme Tours in Istanbul"} or
     * {@code "Walking & Biking Tours in Istanbul"}. Falls back to the page title
     * (with the {@code " - Tripadvisor"} suffix stripped). Returns {@code null}
     * when neither is available, in which case the configured subcategory is used.
     */
    private String resolveCategoryName(Page page) {
        try {
            Locator h1 = page.locator("h1");
            if (h1.count() > 0) {
                String text = h1.first().textContent();
                if (text != null && !text.isBlank()) {
                    return text.trim();
                }
            }
        } catch (Exception e) {
            log.debug("Could not read h1 for category resolution: {}", e.getMessage());
        }
        try {
            String title = page.title();
            if (title != null && !title.isBlank()) {
                String cleaned = title.replaceFirst("\\s*-\\s*Tripadvisor\\s*$", "").trim();
                return cleaned.isBlank() ? null : cleaned;
            }
        } catch (Exception e) {
            log.debug("Could not read page title for category resolution: {}", e.getMessage());
        }
        return null;
    }

    // ==================== "See More" expansion ====================

    /**
     * Collects every attraction product on the listing by alternating between
     * clicking the "See More" button and human-scrolling down the page. The
     * listing is an infinite/virtualized list (e.g. "95 results" with only ~28
     * rows rendered at a time): "See More" loads more items into React state but
     * the rows only appear in the DOM once scrolled into view. So rather than
     * relying on one page snapshot we accumulate the unique product ids we see
     * across the whole scroll+click pass.
     *
     * @return the deduplicated list of products observed, in first-seen order.
     */
    private List<AttractionProductListing> collectAllProducts(Page page, long startedAt, String sourceListingUrl) {
        Map<String, AttractionProductListing> collected = new LinkedHashMap<>();
        int clicks = 0;
        int stableRounds = 0;
        int maxRounds = properties.seeMoreMaxClicks() + 30;
        log.info("TRIPADVISOR_ATTRACTION_PRODUCT_EXPAND_START maxItems={} seeMoreMaxClicks={}",
                properties.maxItems(), properties.seeMoreMaxClicks());

        for (int round = 0; round < maxRounds && collected.size() < properties.maxItems(); round++) {
            int beforeSize = collected.size();
            collectRenderedProducts(page, collected, sourceListingUrl);

            // Scroll down first: the "See More" button only appears after the
            // current batch (~30 items) is scrolled into view, and scrolling also
            // forces the virtualized/infinite list to render its next window.
            humanScrollDown(page);
            pause();

            collectRenderedProducts(page, collected, sourceListingUrl);

            Locator seeMore = findSeeMoreButton(page);
            if (seeMore != null) {
                // Approach the button like a human (scroll, hover, dwell) instead of
                // teleporting the cursor onto it — rapid identically-timed clicks are a
                // strong DataDome bot signal and the main cause of IP bans here.
                approachButtonHumanly(page, seeMore);
                if (clickSeeMore(seeMore, page)) {
                    clicks++;
                    log.info("TRIPADVISOR_ATTRACTION_PRODUCT_SEE_MORE_CLICKED click={} accumulatedProducts={}",
                            clicks, collected.size());
                } else {
                    log.warn("TRIPADVISOR_ATTRACTION_PRODUCT_SEE_MORE_CLICK_FAILED clickAttempt={} — trying scroll fallback", clicks + 1);
                }
            }

            int nowSize = collected.size();
            log.info("TRIPADVISOR_ATTRACTION_PRODUCT_EXPAND_ROUND round={} click={} accumulatedProducts={} before={} elapsedMs={}",
                    round + 1, clicks, nowSize, beforeSize, elapsedMs(startedAt));

            if (nowSize <= beforeSize) {
                stableRounds++;
                if (stableRounds >= 6) {
                    log.info("TRIPADVISOR_ATTRACTION_PRODUCT_EXPAND_DONE reason='no new products for {} consecutive rounds' clicks={} accumulatedProducts={} elapsedMs={}",
                            stableRounds, clicks, nowSize, elapsedMs(startedAt));
                    break;
                }
            } else {
                stableRounds = 0;
            }
        }

        if (collected.size() < properties.maxItems() && clicks >= properties.seeMoreMaxClicks()) {
            log.warn("TRIPADVISOR_ATTRACTION_PRODUCT_EXPAND_DONE reason='reached seeMoreMaxClicks={} safety limit' accumulatedProducts={} elapsedMs={}",
                    properties.seeMoreMaxClicks(), collected.size(), elapsedMs(startedAt));
        }
        log.info("TRIPADVISOR_ATTRACTION_PRODUCT_EXPAND_FINISHED totalClicks={} accumulatedProducts={} targetMaxItems={} elapsedMs={}",
                clicks, collected.size(), properties.maxItems(), elapsedMs(startedAt));
        return new ArrayList<>(collected.values());
    }

    /**
     * Reads the product anchors currently rendered in the DOM and merges the
     * newly seen ids into {@code collected}. Because the list is virtualized we
     * re-read on every scroll round so ids that render only briefly are captured.
     */
    private void collectRenderedProducts(Page page, Map<String, AttractionProductListing> collected, String sourceListingUrl) {
        try {
            Object raw = page.evaluate("""
                    () => {
                      const seen = {};
                      document.querySelectorAll('a[href*="AttractionProductReview-"]').forEach(a => {
                        const href = a.getAttribute('href') || '';
                        const m = href.match(/-d(\\d+)-/);
                        if (!m) return;
                        const id = m[1];
                        let name = (a.getAttribute('aria-label') || '').trim();
                        if (!name) name = (a.textContent || '').trim();
                        const entry = seen[id];
                        if (!entry) { seen[id] = { id, url: href, name }; }
                        else if (!entry.name && name) { entry.name = name; }
                      });
                      return Object.values(seen);
                    }
                    """);
            if (!(raw instanceof List<?> items)) {
                return;
            }
            for (Object item : items) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                Object idObj = map.get("id");
                Object urlObj = map.get("url");
                if (idObj == null || urlObj == null) {
                    continue;
                }
                String normalizedUrl = normalizeProductUrl(String.valueOf(urlObj));
                if (normalizedUrl == null) {
                    continue;
                }
                Long tripadvisorId;
                try {
                    tripadvisorId = Long.parseLong(String.valueOf(idObj));
                } catch (NumberFormatException e) {
                    continue;
                }
                Object nameObj = map.get("name");
                String name = cleanName(nameObj == null ? null : String.valueOf(nameObj));
                AttractionProductListing listing = AttractionProductListing.builder()
                        .tripadvisorId(tripadvisorId)
                        .url(normalizedUrl)
                        .name(name)
                        .sourceListingUrl(sourceListingUrl)
                        .build();
                AttractionProductListing existing = collected.get(normalizedUrl);
                if (existing == null || (isBlank(existing.name()) && !isBlank(name))) {
                    collected.put(normalizedUrl, listing);
                }
            }
        } catch (Exception e) {
            log.debug("collectRenderedProducts failed: {}", e.getMessage());
        }
    }

    private String normalizeProductUrl(String href) {
        if (href == null || href.isBlank()) {
            return null;
        }
        String cleanHref = href.split("#", 2)[0].split("\\?", 2)[0];
        String absolute = cleanHref.startsWith("http") ? cleanHref : "https://www.tripadvisor.com" + cleanHref;
        try {
            URI uri = URI.create(absolute);
            return uri.getScheme() + "://" + uri.getHost() + uri.getPath();
        } catch (Exception e) {
            return null;
        }
    }

    private String cleanName(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        if (trimmed.startsWith("Photo of ")
                || trimmed.startsWith("Review of ")
                || trimmed.startsWith("Previous")
                || trimmed.startsWith("Next")) {
            return null;
        }
        return trimmed.replaceFirst("^\\d+\\.\\s+", "").trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Attempts to click the See More button, returning {@code true} on success.
     * Falls back to scrolling the page down (which may reveal/load the next batch).
     */
    private boolean clickSeeMore(Locator seeMore, Page page) {
        try {
            seeMore.click(new Locator.ClickOptions().setTimeout(15_000));
            return true;
        } catch (Exception e) {
            log.debug("See More click failed (will scroll instead): {}", e.getMessage());
            return false;
        }
    }

    /**
     * Humanises the approach to the "See More" button: occasionally wander the
     * mouse elsewhere first, scroll the button into view, dwell, then hover near
     * it before the click. This breaks the deterministic teleport-and-click
     * pattern that DataDome fingerprints on heavily-interactive product pages.
     */
    private void approachButtonHumanly(Page page, Locator seeMore) {
        try {
            // Real users don't go straight for a button — sometimes drift the mouse first.
            if (ThreadLocalRandom.current().nextInt(4) == 0) {
                page.mouse().move(120 + ThreadLocalRandom.current().nextInt(800),
                        140 + ThreadLocalRandom.current().nextInt(350));
                Thread.sleep(350 + ThreadLocalRandom.current().nextLong(700));
            }
            seeMore.scrollIntoViewIfNeeded(new Locator.ScrollIntoViewIfNeededOptions().setTimeout(10_000));
            pause();
            // Hover over the button area before the click rather than teleporting to it.
            page.mouse().move(300 + ThreadLocalRandom.current().nextInt(500),
                    250 + ThreadLocalRandom.current().nextInt(350));
            Thread.sleep(250 + ThreadLocalRandom.current().nextLong(600));
        } catch (Exception e) {
            log.debug("approachButtonHumanly failed: {}", e.getMessage());
        }
    }

    /**
     * Locates the "See More" style button with several selector strategies —
     * TripAdvisor occasionally renames the automation attributes, so the
     * visible text match is tried first.
     */
    private Locator findSeeMoreButton(Page page) {
        String[] selectors = {
                "button:has-text('see more')",
                "a:has-text('see more')",
                "[data-automation='see-more']",
                "[data-automation*='see-more']",
                "[data-test-target*='see-more']",
                "button[aria-label*='See More']",
                "button[aria-label*='more']"
        };
        for (String selector : selectors) {
            try {
                Locator locator = page.locator(selector);
                int count = locator.count();
                // IMPORTANT: iterate every match, not just the first. The page also
                // contains a hidden "See more dates" button (for the reservation date
                // picker) that appears before the product "See more" button in the DOM;
                // checking only .first() would reject the selector and no products load.
                for (int i = 0; i < count; i++) {
                    Locator candidate = locator.nth(i);
                    if (!candidate.isVisible()) {
                        continue;
                    }
                    String text = candidate.innerText();
                    if (text == null) {
                        continue;
                    }
                    String lower = text.trim().toLowerCase();
                    if (lower.contains("see more") && !lower.contains("date")) {
                        log.debug("See More button matched selector='{}' index={} text='{}'", selector, i, text.trim());
                        return candidate;
                    }
                }
            } catch (Exception e) {
                log.debug("See More selector '{}' failed: {}", selector, e.getMessage());
            }
        }
        return null;
    }

    /**
     * Scrolls the listing downward in a human-like way instead of jumping
     * straight to the bottom. Steps through viewport-sized scrolls with jittered
     * pauses and occasional mouse wheel flicks. TripAdvisor's product list lives
     * inside an inner scroll container (not the window), so we first locate the
     * scrollable element hosting a product link and advance its scrollTop;
     * otherwise we fall back to window scrolling. This forces the virtualized
     * list to render rows that have been loaded in to state by "See More".
     */
    private void humanScrollDown(Page page) {
        try {
            int steps = 3 + ThreadLocalRandom.current().nextInt(4); // 3-6 scroll steps
            for (int i = 0; i < steps; i++) {
                page.evaluate("""
                        () => {
                          const anchor = document.querySelector('a[href*="AttractionProductReview-"]');
                          if (anchor) {
                            let el = anchor.parentElement;
                            while (el && el !== document.body) {
                              if (el.scrollHeight > el.clientHeight && getComputedStyle(el).overflowY !== 'visible') {
                                el.scrollTop = Math.min(el.scrollTop + el.clientHeight, el.scrollHeight);
                                return;
                              }
                              el = el.parentElement;
                            }
                          }
                          window.scrollBy(0, Math.round(window.innerHeight * 0.7));
                        }
                        """);
                Thread.sleep(500 + ThreadLocalRandom.current().nextLong(1000));
                if (ThreadLocalRandom.current().nextInt(3) == 0) {
                    page.mouse().wheel(0, 120 + ThreadLocalRandom.current().nextInt(180));
                    Thread.sleep(300 + ThreadLocalRandom.current().nextLong(600));
                }
            }
            // Small dwell at the bottom before any next action.
            Thread.sleep(700 + ThreadLocalRandom.current().nextLong(1200));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("Human scroll down failed: {}", e.getMessage());
        }
    }

    // ==================== DataDome Challenge Handling ====================

    private boolean waitForRealContent(Page page, String phase) {
        long deadline = System.currentTimeMillis() + 45_000;
        int attempt = 0;
        boolean challengeDetected = false;

        while (System.currentTimeMillis() < deadline) {
            attempt++;
            try {
                String currentHtml = page.content();
                String currentTitle = page.title();

                if (currentHtml.contains(PRODUCT_LINK_MARKER)) {
                    log.info("TRIPADVISOR_ATTRACTION_PRODUCT_CONTENT_FOUND phase={} attempt={} title='{}' htmlLen={}",
                            phase, attempt, currentTitle, currentHtml.length());
                    return true;
                }

                if (isDataDomeChallenge(currentHtml, currentTitle)) {
                    if (!challengeDetected) {
                        challengeDetected = true;
                        log.info("TRIPADVISOR_ATTRACTION_PRODUCT_DATADOME_DETECTED phase={} attempt={} title='{}' htmlLen={}",
                                phase, attempt, currentTitle, currentHtml.length());
                    }
                } else if (currentHtml.length() > 15_000) {
                    Thread.sleep(3000);
                    currentHtml = page.content();
                    if (currentHtml.contains(PRODUCT_LINK_MARKER)) {
                        return true;
                    }
                    if (currentHtml.length() > 15_000 && !isDataDomeChallenge(currentHtml, page.title())) {
                        log.info("TRIPADVISOR_ATTRACTION_PRODUCT_CONTENT_ACCEPTED phase={} attempt={} htmlLen={}",
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

        log.warn("TRIPADVISOR_ATTRACTION_PRODUCT_CONTENT_TIMEOUT phase={} attempts={}", phase, attempt);
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

    private static java.util.Map<String, String> browserHeaders() {
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("Accept-Language", "en-US,en;q=0.9");
        headers.put("Upgrade-Insecure-Requests", "1");
        return headers;
    }

    // ==================== Helper Methods ====================

    /**
     * Navigates the given page to {@code url}, transparently recovering when the
     * DataDome challenge auto-reload closes the original page/tab.
     */
    private Page navigateSafely(BrowserContext context, Page page, String url, String phase) {
        try {
            page.navigate(url, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(properties.navigationTimeoutMs()));
            return page;
        } catch (Exception e) {
            log.warn("TRIPADVISOR_ATTRACTION_PRODUCT_NAVIGATE_RECOVER phase={} url={} error={} — recreating page and retrying",
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
                log.error("TRIPADVISOR_ATTRACTION_PRODUCT_NAVIGATE_RECOVER_FAILED phase={} url={} error={}",
                        phase, url, retryError.getMessage());
                throw retryError;
            }
        }
    }

    private void pause() {
        long min = properties.minDelayMs();
        long max = properties.maxDelayMs();
        long delay = max <= min ? min : ThreadLocalRandom.current().nextLong(min, max + 1);
        if (delay <= 0) {
            return;
        }
        try {
            log.info("Tripadvisor attraction product crawler delay: sleeping {}ms before next action", delay);
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during Tripadvisor attraction product crawler delay", e);
        }
    }

    private void logProductSummary(List<AttractionProductListing> products) {
        if (products.isEmpty()) {
            log.warn("TRIPADVISOR_ATTRACTION_PRODUCT_SUMMARY productCount=0 message=No attraction products extracted from rendered page");
            return;
        }
        for (int i = 0; i < products.size(); i++) {
            AttractionProductListing product = products.get(i);
            log.info("TRIPADVISOR_ATTRACTION_PRODUCT_SUMMARY index={} id={} name='{}' url={}",
                    i + 1, product.tripadvisorId(), product.name(), product.url());
        }
    }

    private int byteSize(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private String saveHtmlSnapshot(String html) {
        try {
            Files.createDirectories(Path.of("data", "tripadvisor-debug", "attraction-products"));
            Path path = Path.of("data", "tripadvisor-debug", "attraction-products", "attraction-products-listing.html");
            Files.writeString(path, html == null ? "" : html, StandardCharsets.UTF_8);
            return path.toString();
        } catch (Exception e) {
            log.warn("Failed to save Tripadvisor attraction products HTML snapshot. error={}", e.getMessage());
            return "<not-saved>";
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

}
