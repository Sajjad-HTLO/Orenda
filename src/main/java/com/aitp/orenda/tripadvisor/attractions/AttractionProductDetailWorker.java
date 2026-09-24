package com.aitp.orenda.tripadvisor.attractions;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitUntilState;
import com.aitp.orenda.tripadvisor.config.TripadvisorCrawlerProperties;
import com.aitp.orenda.tripadvisor.image.ImageSaver;
import com.aitp.orenda.tripadvisor.util.DiskSpaceGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Stage 2 worker for the attraction products crawler: opens each individual
 * Tripadvisor attraction product URL ({@code AttractionProductReview-}),
 * extracts detailed data and image URLs, persists the detail, then downloads
 * and stores the image binaries. Each invocation opens its own Playwright/
 * browser instance and reuses the DataDome warm-up + human-behavior strategy.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "tripadvisor.crawler.enabled", havingValue = "true")
public class AttractionProductDetailWorker {

    private static final String TRIPADVISOR_HOMEPAGE = "https://www.tripadvisor.com/";
    private static final String PRODUCT_LINK_MARKER = "AttractionProductReview-";
    private static final String DATADOME_MARKER = "captcha-delivery.com";
    private static final String DATADOME_CHALLENGE_TITLE = "tripadvisor.com";
    private static final long DEBUG_SNAPSHOT_MAX_BYTES = 150_000_000L;
    private static final java.util.regex.Pattern REVIEW_URL_PATTERN = java.util.regex.Pattern.compile(
            "(https://www\\.tripadvisor\\.com/)(AttractionProductReview|Attraction_Review|Attraction_Products)-(g\\d+)-(d\\d+)-(.+)\\.html");

    private final TripadvisorCrawlerProperties properties;
    private final AttractionProductDetailParser detailParser;
    private final AttractionProductReviewParser reviewParser;
    private final AttractionProductRepository productRepository;
    private final CrawledReviewRepository crawledReviewRepository;
    private final ImageSaver imageSaver;
    private final DiskSpaceGuard diskSpaceGuard;

    public AttractionProductDetailWorker(
            TripadvisorCrawlerProperties properties,
            AttractionProductDetailParser detailParser,
            AttractionProductReviewParser reviewParser,
            AttractionProductRepository productRepository,
            CrawledReviewRepository crawledReviewRepository,
            ImageSaver imageSaver,
            DiskSpaceGuard diskSpaceGuard) {
        this.properties = properties;
        this.detailParser = detailParser;
        this.reviewParser = reviewParser;
        this.productRepository = productRepository;
        this.crawledReviewRepository = crawledReviewRepository;
        this.imageSaver = imageSaver;
        this.diskSpaceGuard = diskSpaceGuard;
    }

    public AttractionProductDetailCrawlResult crawl(AttractionProductListing listing) {
        Path userDataDir = ensureUserDataDir();
        if (!diskSpaceGuard.hasEnoughSpace(userDataDir)) {
            log.error("TRIPADVISOR_ATTRACTION_PRODUCT_DETAIL_DISK_FULL tripadvisorId={} url={} message=Free disk space {} bytes is below minimum {} bytes. Skipping.",
                    listing.tripadvisorId(), listing.url(), diskSpaceGuard.freeBytes(userDataDir), diskSpaceGuard.minFreeBytes());
            return AttractionProductDetailCrawlResult.failure(
                    "Disk space too low to launch browser; free at least " + diskSpaceGuard.minFreeBytes() + " bytes");
        }
        // Firefox (native user agent) passes DataDome where Chromium is persistently
        // challenged. Keep the default Firefox UA — the browser is fully self-consistent.
        try (Playwright playwright = Playwright.create();
             BrowserContext context = playwright.firefox().launchPersistentContext(userDataDir, launchOptions())) {
            return crawlWithContext(listing, context, null);
        }
    }

    /**
     * Crawls the given listing's detail page reusing the caller's shared
     * {@link BrowserContext}. Reusing one context (instead of a fresh browser
     * per product) lets the DataDome cookie established during the first
     * warmup persist across every subsequent product, which dramatically
     * reduces how often Tripadvisor returns the "access temporarily restricted"
     * challenge.
     *
     * @param categoryName the category resolved from the listing page heading
     *                     (e.g. "Cultural & Theme Tours in Istanbul"); when
     *                     {@code null} the configured subcategory is used.
     */
    public AttractionProductDetailCrawlResult crawl(AttractionProductListing listing, BrowserContext context, String categoryName) {
        Path userDataDir = ensureUserDataDir();
        if (!diskSpaceGuard.hasEnoughSpace(userDataDir)) {
            log.error("TRIPADVISOR_ATTRACTION_PRODUCT_DETAIL_DISK_FULL tripadvisorId={} url={} message=Free disk space {} bytes is below minimum {} bytes. Skipping.",
                    listing.tripadvisorId(), listing.url(), diskSpaceGuard.freeBytes(userDataDir), diskSpaceGuard.minFreeBytes());
            return AttractionProductDetailCrawlResult.failure(
                    "Disk space too low to launch browser; free at least " + diskSpaceGuard.minFreeBytes() + " bytes");
        }
        return crawlWithContext(listing, context, categoryName);
    }

    private AttractionProductDetailCrawlResult crawlWithContext(AttractionProductListing listing, BrowserContext context, String categoryName) {
        long startedAt = System.currentTimeMillis();
        log.info("TRIPADVISOR_ATTRACTION_PRODUCT_DETAIL_START tripadvisorId={} url={} sourceListingUrl={}",
                listing.tripadvisorId(), listing.url(), listing.sourceListingUrl());

        Page page = null;
        try {
            // Reuse the page that launchPersistentContext already opened instead of
            // creating a new one per product. Opening an extra page leaves the
            // initial about:blank window open, and DataDome treats the second blank
            // window as a bot signal (temporary access block).
            page = context.pages().isEmpty() ? context.newPage() : context.pages().get(0);
            // Close any extra pages (e.g. a restored-session window) so the browser
            // keeps a single window — extra blank windows are a DataDome bot signal.
            while (context.pages().size() > 1) {
                try {
                    context.pages().get(context.pages().size() - 1).close();
                } catch (Exception ignore) {
                    break;
                }
            }
            page.setDefaultNavigationTimeout(properties.navigationTimeoutMs());
            injectStealthScripts(page);

            // Warm up homepage to establish DataDome cookies
            log.info("TRIPADVISOR_ATTRACTION_PRODUCT_DETAIL_WARMUP tripadvisorId={} url={} message=Visiting homepage for DataDome cookie warming",
                    listing.tripadvisorId(), listing.url());
            page.navigate(TRIPADVISOR_HOMEPAGE, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(properties.navigationTimeoutMs()));
            boolean warmupOk = waitForRealContent(page, listing, "WARMUP");
            if (warmupOk) {
                // performHumanBehavior already includes its own pause, so no extra
                // pause() is needed here (it doubled the per-product delay).
                performHumanBehavior(page);
            } else {
                log.warn("TRIPADVISOR_ATTRACTION_PRODUCT_DETAIL_WARMUP_WARNING tripadvisorId={} url={} message=Homepage warmup may not have fully resolved",
                        listing.tripadvisorId(), listing.url());
                pause();
            }

            // Navigate to the product detail page
            log.info("TRIPADVISOR_ATTRACTION_PRODUCT_DETAIL_NAVIGATE tripadvisorId={} url={} message=Navigating to attraction product detail page",
                    listing.tripadvisorId(), listing.url());
            page.navigate(listing.url(), new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(properties.navigationTimeoutMs()));

            boolean contentReady = waitForRealContent(page, listing, "PRODUCT_DETAIL");

            if (!contentReady) {
                log.warn("TRIPADVISOR_ATTRACTION_PRODUCT_DETAIL_RETRY_RELOAD tripadvisorId={} url={} message=Content not found, reloading page (retry 1)",
                        listing.tripadvisorId(), listing.url());
                pause();
                try {
                    page.reload(new Page.ReloadOptions()
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                            .setTimeout(properties.navigationTimeoutMs()));
                } catch (Exception e) {
                    log.debug("Reload failed: {}", e.getMessage());
                }
                contentReady = waitForRealContent(page, listing, "RETRY1");
            }

            if (!contentReady) {
                log.warn("TRIPADVISOR_ATTRACTION_PRODUCT_DETAIL_RETRY_FULL tripadvisorId={} url={} message=Content still not found, doing full re-warmup + re-navigate (retry 2)",
                        listing.tripadvisorId(), listing.url());
                pause();
                try {
                    page.navigate(TRIPADVISOR_HOMEPAGE, new Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                            .setTimeout(properties.navigationTimeoutMs()));
                    waitForRealContent(page, listing, "RETRY_WARMUP");
                    performHumanBehavior(page);
                    pause();
                    page.navigate(listing.url(), new Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                            .setTimeout(properties.navigationTimeoutMs()));
                    contentReady = waitForRealContent(page, listing, "RETRY2");
                } catch (Exception e) {
                    log.debug("Full retry navigation failed: {}", e.getMessage());
                }
            }

            performHumanBehavior(page);
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            String html = page.content();
            String title = page.title();
            boolean stillBlocked = isDataDomeChallenge(html, title);
            String snapshotPath = saveHtmlSnapshot(listing, html);

            log.info("TRIPADVISOR_ATTRACTION_PRODUCT_DETAIL_FETCHED tripadvisorId={} url={} title='{}' htmlChars={} htmlBytes={} stillBlocked={} snapshotPath={} elapsedMs={}",
                    listing.tripadvisorId(), listing.url(), title, html == null ? 0 : html.length(),
                    byteSize(html), stillBlocked, snapshotPath, elapsedMs(startedAt));

            if (stillBlocked) {
                log.error("TRIPADVISOR_ATTRACTION_PRODUCT_DETAIL_BLOCKED tripadvisorId={} url={} reason='DataDome challenge could not be resolved after all retries' title='{}' snapshotPath={} elapsedMs={}",
                        listing.tripadvisorId(), listing.url(), title, snapshotPath, elapsedMs(startedAt));
                return AttractionProductDetailCrawlResult.failure("DataDome challenge could not be resolved after all retries");
            }

            AttractionProductDetail detail = detailParser.parse(html, listing.url(), listing.sourceListingUrl());
            log.info("TRIPADVISOR_ATTRACTION_PRODUCT_DETAIL_EXTRACTED tripadvisorId={} url={} name='{}' rating={} reviewCount={} price='{}' duration='{}' imageCount={} elapsedMs={}",
                    detail.tripadvisorId(), detail.url(), detail.name(), detail.rating(), detail.reviewCount(),
                    detail.price(), detail.duration(),
                    detail.imageUrls() == null ? 0 : detail.imageUrls().size(), elapsedMs(startedAt));

            boolean alreadyHasImages = productRepository.hasImages(detail.tripadvisorId());
            int persistedRows = productRepository.upsertProductDetail(detail, categoryName);
            log.info("TRIPADVISOR_ATTRACTION_PRODUCT_DETAIL_PERSISTED tripadvisorId={} url={} affectedRows={} elapsedMs={}",
                    detail.tripadvisorId(), detail.url(), persistedRows, elapsedMs(startedAt));

            persistReviews(listing, html, page);

            int imagesStored = 0;
            if (alreadyHasImages) {
                log.info("TRIPADVISOR_ATTRACTION_PRODUCT_DETAIL_IMAGES_SKIP tripadvisorId={} url={} reason='images already present'",
                        detail.tripadvisorId(), detail.url());
            } else {
                imagesStored = saveImages(detail.tripadvisorId(), detail.imageUrls());
                log.info("TRIPADVISOR_ATTRACTION_PRODUCT_DETAIL_IMAGES tripadvisorId={} url={} imagesStored={} elapsedMs={}",
                        detail.tripadvisorId(), detail.url(), imagesStored, elapsedMs(startedAt));
            }

            log.info("TRIPADVISOR_ATTRACTION_PRODUCT_DETAIL_DONE tripadvisorId={} url={} totalElapsedMs={}",
                    detail.tripadvisorId(), detail.url(), elapsedMs(startedAt));
            return AttractionProductDetailCrawlResult.success(detail);
        } catch (Exception e) {
            log.error("TRIPADVISOR_ATTRACTION_PRODUCT_DETAIL_FAILED tripadvisorId={} url={} elapsedMs={} errorType={} error={}",
                    listing.tripadvisorId(), listing.url(), elapsedMs(startedAt), e.getClass().getSimpleName(), e.getMessage(), e);
            return AttractionProductDetailCrawlResult.failure(e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            // Keep the shared page open for the next product (single browser
            // window). The manager recycles the whole context on a browser crash.
            if (page == null) {
                log.debug("Tripadvisor attraction product detail: no page created for tripadvisorId={}", listing.tripadvisorId());
            }
        }
    }

    /**
     * Opens a single Playwright + persistent {@link BrowserContext} that can be
     * shared across every detail-product crawl in a run. Sharing the context
     * lets the DataDome cookie persist between products, which is what actually
     * keeps Tripadvisor from answering with the "access temporarily restricted"
     * challenge.
     */
    public SharedBrowser openSharedBrowser() {
        Path userDataDir = ensureUserDataDir();
        if (!diskSpaceGuard.hasEnoughSpace(userDataDir)) {
            throw new IllegalStateException("Disk space too low to launch browser; free at least "
                    + diskSpaceGuard.minFreeBytes() + " bytes");
        }
        Playwright playwright = Playwright.create();
        BrowserContext context = playwright.firefox().launchPersistentContext(userDataDir, launchOptions());
        return new SharedBrowser(playwright, context);
    }

    /**
     * Holds a shared {@link Playwright} + {@link BrowserContext} and releases
     * both when closed.
     */
    public static final class SharedBrowser implements AutoCloseable {
        private final Playwright playwright;
        private final BrowserContext context;

        SharedBrowser(Playwright playwright, BrowserContext context) {
            this.playwright = playwright;
            this.context = context;
        }

        public BrowserContext context() {
            return context;
        }

        @Override
        public void close() {
            try {
                context.close();
            } catch (Exception ignore) {
                // context may already be closed by a browser crash
            }
            try {
                playwright.close();
            } catch (Exception ignore) {
                // best-effort cleanup
            }
        }
    }

    private int saveImages(long tripadvisorId, java.util.List<String> imageUrls) {
        try {
            return imageSaver.save(tripadvisorId, "T", "tripadvisor", imageUrls);
        } catch (Exception e) {
            log.warn("Tripadvisor attraction product image saving failed (non-fatal). tripadvisorId={}, error={}",
                    tripadvisorId, e.getMessage());
            return 0;
        }
    }

    /**
     * Parses the rendered product page for traveler reviews and persists them
     * (with any per-aspect sub-ratings) against the POI. When the first parse
     * yields nothing, the equivalent {@code Attraction_Products-} page and then
     * the server-rendered {@code ShowUserReviews-} page are tried before giving
     * up. Failures are non-fatal so review crawling never aborts the
     * detail/image stage.
     */
    private void persistReviews(AttractionProductListing listing, String html, Page page) {
        try {
            java.util.List<CrawledPoiReview> reviews = safeParseReviews(html, listing.url());
            if ((reviews == null || reviews.isEmpty()) && page != null) {
                for (String candidate : reviewFallbackUrls(listing.url())) {
                    if (candidate == null) {
                        continue;
                    }
                    ReviewPageAttempt attempt = fetchAndParseReviewPage(listing, page, candidate);
                    if (attempt.blocked) {
                        log.info("TRIPADVISOR_ATTRACTION_PRODUCT_REVIEWS_BLOCKED tripadvisorId={} url={} candidate={}",
                                listing.tripadvisorId(), listing.url(), candidate);
                        return;
                    }
                    if (attempt.reviews != null && !attempt.reviews.isEmpty()) {
                        reviews = attempt.reviews;
                        break;
                    }
                }
            }
            if (reviews == null || reviews.isEmpty()) {
                log.info("TRIPADVISOR_ATTRACTION_PRODUCT_REVIEWS_NONE tripadvisorId={} url={}",
                        listing.tripadvisorId(), listing.url());
                // A real attempt found no inline reviews; mark the product so the
                // sweep stops re-crawling it forever (re-enable by clearing the flag).
                productRepository.markReviewsAttempted(listing.tripadvisorId());
                return;
            }
            java.util.UUID poiId = productRepository.findPoiIdByTripadvisorId(listing.tripadvisorId());
            if (poiId == null) {
                log.warn("TRIPADVISOR_ATTRACTION_PRODUCT_REVIEWS_SKIP tripadvisorId={} url={} reason='no poi row found'",
                        listing.tripadvisorId(), listing.url());
                return;
            }
            int saved = crawledReviewRepository.save(poiId, reviews);
            log.info("TRIPADVISOR_ATTRACTION_PRODUCT_REVIEWS_PERSISTED tripadvisorId={} url={} reviews={} saved={}",
                    listing.tripadvisorId(), listing.url(), reviews.size(), saved);
        } catch (Exception e) {
            log.warn("TRIPADVISOR_ATTRACTION_PRODUCT_REVIEWS_FAILED tripadvisorId={} url={} error={}",
                    listing.tripadvisorId(), listing.url(), e.getMessage());
        }
    }

    /**
     * Holds the outcome of one review-page fallback attempt: either the parsed
     * reviews (empty when the page rendered no inline reviews), or a flag saying
     * the page was DataDome-blocked and must be retried on a later round.
     */
    private static final class ReviewPageAttempt {
        private final java.util.List<CrawledPoiReview> reviews;
        private final boolean blocked;

        ReviewPageAttempt(java.util.List<CrawledPoiReview> reviews, boolean blocked) {
            this.reviews = reviews;
            this.blocked = blocked;
        }
    }

    /**
     * Navigates to a candidate review page and parses it. Returns the parsed
     * reviews (possibly empty when the page rendered none), or an attempt
     * flagged as blocked when DataDome intercepted the page.
     */
    private ReviewPageAttempt fetchAndParseReviewPage(AttractionProductListing listing, Page page, String reviewsUrl) {
        try {
            log.info("TRIPADVISOR_ATTRACTION_PRODUCT_REVIEWS_PAGE_NAVIGATE tripadvisorId={} url={} reviewsUrl={}",
                    listing.tripadvisorId(), listing.url(), reviewsUrl);
            page.navigate(reviewsUrl, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(properties.navigationTimeoutMs()));
            boolean contentReady = waitForRealContent(page, listing, "REVIEWS_FALLBACK");
            performHumanBehavior(page);
            if (contentReady) {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            String html = page.content();
            String title = page.title();
            if (isDataDomeChallenge(html, title)) {
                return new ReviewPageAttempt(null, true);
            }
            java.util.List<CrawledPoiReview> reviews = safeParseReviews(html, reviewsUrl);
            log.info("TRIPADVISOR_ATTRACTION_PRODUCT_REVIEWS_PAGE_FALLBACK tripadvisorId={} url={} reviewsUrl={} extractedReviews={}",
                    listing.tripadvisorId(), listing.url(), reviewsUrl,
                    reviews == null ? 0 : reviews.size());
            return new ReviewPageAttempt(reviews, false);
        } catch (Exception e) {
            log.warn("TRIPADVISOR_ATTRACTION_PRODUCT_REVIEWS_PAGE_ERROR tripadvisorId={} url={} reviewsUrl={} error={}",
                    listing.tripadvisorId(), listing.url(), reviewsUrl, e.getMessage());
            return new ReviewPageAttempt(null, false);
        }
    }

    /**
     * Parses reviews from the given HTML, swallowing parser errors so they can
     * be treated as "no reviews found at this stage" and trigger the fallback.
     */
    private java.util.List<CrawledPoiReview> safeParseReviews(String html, String sourceUrl) {
        try {
            return reviewParser.parse(html, sourceUrl, properties.maxReviewsPerPoi());
        } catch (Exception e) {
            log.warn("TRIPADVISOR_ATTRACTION_PRODUCT_REVIEWS_PARSE_ERROR tripadvisorId={} url={} error={}",
                    sourceUrl, e.getMessage());
            return null;
        }
    }

    /**
     * Candidate review pages to try when the primary product page shows no
     * inline review cards: first the equivalent bookable-product page
     * ({@code Attraction_Products-}), then the classic server-rendered
     * {@code ShowUserReviews-} review list.
     */
    private java.util.List<String> reviewFallbackUrls(String productUrl) {
        java.util.List<String> urls = new java.util.ArrayList<>();
        if (productUrl == null) {
            return urls;
        }
        java.util.regex.Matcher matcher = REVIEW_URL_PATTERN.matcher(productUrl);
        if (!matcher.find()) {
            return urls;
        }
        String domain = matcher.group(1);
        String geo = matcher.group(3);
        String id = matcher.group(4);
        String slug = matcher.group(5);
        urls.add(domain + "Attraction_Products-" + geo + "-" + id + "-" + slug + ".html");
        urls.add(domain + "ShowUserReviews-" + geo + "-" + id + "-r0-" + slug + ".html");
        return urls;
    }

    // ==================== DataDome handling ====================

    private boolean waitForRealContent(Page page, AttractionProductListing listing, String phase) {
        long deadline = System.currentTimeMillis() + 45_000;
        int attempt = 0;
        boolean challengeDetected = false;

        while (System.currentTimeMillis() < deadline) {
            attempt++;
            try {
                String currentHtml = page.content();
                String currentTitle = page.title();

                if (currentHtml.length() > 15_000 && !isDataDomeChallenge(currentHtml, currentTitle)) {
                    log.info("TRIPADVISOR_ATTRACTION_PRODUCT_DETAIL_CONTENT_FOUND tripadvisorId={} url={} phase={} attempt={} title='{}' htmlLen={}",
                            listing.tripadvisorId(), listing.url(), phase, attempt, currentTitle, currentHtml.length());
                    return true;
                }

                if (isDataDomeChallenge(currentHtml, currentTitle)) {
                    if (!challengeDetected) {
                        challengeDetected = true;
                        log.info("TRIPADVISOR_ATTRACTION_PRODUCT_DETAIL_DATADOME_DETECTED tripadvisorId={} url={} phase={} attempt={} title='{}' htmlLen={}",
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

        log.warn("TRIPADVISOR_ATTRACTION_PRODUCT_DETAIL_CONTENT_TIMEOUT tripadvisorId={} url={} phase={} attempts={}",
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

    private BrowserType.LaunchPersistentContextOptions launchOptions() {
        // Firefox (native user agent) passes DataDome where Chromium is persistently
        // challenged. Keep the default Firefox UA — the browser is fully self-consistent.
        return new BrowserType.LaunchPersistentContextOptions()
                .setHeadless(false)
                .setLocale("en-US")
                .setTimezoneId("Europe/Istanbul")
                .setViewportSize(1366, 768)
                .setExtraHTTPHeaders(browserHeaders());
    }

    private static java.util.Map<String, String> browserHeaders() {
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("Accept-Language", "en-US,en;q=0.9");
        headers.put("Upgrade-Insecure-Requests", "1");
        return headers;
    }

    // ==================== Helpers ====================

    private void pause() {
        long min = properties.minDelayMs();
        long max = properties.maxDelayMs();
        long delay = max <= min ? min : ThreadLocalRandom.current().nextLong(min, max + 1);
        if (delay <= 0) {
            return;
        }
        try {
            log.info("Tripadvisor attraction product crawler delay: sleeping {}ms before next request", delay);
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during Tripadvisor attraction product crawler delay", e);
        }
    }

    private int byteSize(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private String saveHtmlSnapshot(AttractionProductListing listing, String html) {
        try {
            Path dir = Path.of("data", "tripadvisor-debug", "attraction-products");
            Files.createDirectories(dir);
            Path path = dir.resolve("product-" + listing.tripadvisorId() + ".html");
            Files.writeString(path, html == null ? "" : html, StandardCharsets.UTF_8);
            pruneHtmlSnapshots(dir);
            return path.toString();
        } catch (Exception e) {
            log.warn("Failed to save Tripadvisor attraction product detail HTML snapshot. tripadvisorId={}, error={}",
                    listing.tripadvisorId(), e.getMessage());
            return "<not-saved>";
        }
    }

    private void pruneHtmlSnapshots(Path dir) {
        java.util.List<Path> files = new java.util.ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            stream.forEach(files::add);
        } catch (Exception e) {
            return;
        }
        files.sort(java.util.Comparator.comparingLong(p -> {
            try {
                return Files.getLastModifiedTime(p).toMillis();
            } catch (Exception e) {
                return 0L;
            }
        }));
        long total = 0;
        for (Path f : files) {
            try {
                total += Files.size(f);
            } catch (Exception e) {
                // ignore unreadable file
            }
        }
        for (Path f : files) {
            if (total <= DEBUG_SNAPSHOT_MAX_BYTES) {
                break;
            }
            try {
                long size = Files.size(f);
                Files.deleteIfExists(f);
                total -= size;
            } catch (Exception e) {
                log.debug("Failed to prune HTML snapshot {}: {}", f, e.getMessage());
            }
        }
    }

    private long elapsedMs(long startedAt) {
        return System.currentTimeMillis() - startedAt;
    }

    private Path ensureUserDataDir() {
        try {
            Path dir = Path.of(properties.browserProfileDir());
            Files.createDirectories(dir);
            return dir;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create persistent browser profile directory", e);
        }
    }
}
