package com.aitp.orenda.tripadvisor.attractions;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Crawls the Tripadvisor attraction listing and enriches each product's detail
 * page (images + reviews).
 * <p>
 * On every listing page the crawler first detects whether a "See More" button
 * is present and clicks it repeatedly to expand the lazy-loaded product list.
 * When no "See More" button is found (category listings that use
 * {@code -oa{offset}-} pagination instead), the manager generates the next
 * page URL automatically. After each page the next URL is persisted back to
 * {@code application.properties} so a restart resumes where it left off.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "tripadvisor.crawler.attraction-products.enabled", havingValue = "true")
public class AttractionProductCrawlerManager {

    private final AttractionProductCrawlerProperties properties;
    private final AttractionProductPaginationGenerator paginationGenerator;
    private final AttractionProductListingWorker listingWorker;
    private final AttractionProductDetailWorker detailWorker;
    private final AttractionProductRepository productRepository;
    private final AttractionProductPropertiesFileUpdater propertiesFileUpdater;

    private final Set<String> processedProductUrls = ConcurrentHashMap.newKeySet();

    public AttractionProductCrawlerManager(
            AttractionProductCrawlerProperties properties,
            AttractionProductPaginationGenerator paginationGenerator,
            AttractionProductListingWorker listingWorker,
            AttractionProductDetailWorker detailWorker,
            AttractionProductRepository productRepository,
            AttractionProductPropertiesFileUpdater propertiesFileUpdater) {
        this.properties = properties;
        this.paginationGenerator = paginationGenerator;
        this.listingWorker = listingWorker;
        this.detailWorker = detailWorker;
        this.productRepository = productRepository;
        this.propertiesFileUpdater = propertiesFileUpdater;
    }

    public void crawl() {
        long startedAt = System.currentTimeMillis();
        log.info("Tripadvisor attraction product crawler manager starting. baseUrl={}, pageSize={}, maxEmptyPages={}, concurrency={}, maxItems={}, seeMoreMaxClicks={}, headless={}, listingMaxAttempts={}, listingRetryDelayMs={}, skipListingIfPresent={}",
                properties.baseUrl(), properties.pageSize(), properties.maxEmptyPages(),
                properties.concurrency(), properties.maxItems(), properties.seeMoreMaxClicks(), properties.headless(),
                properties.listingMaxAttempts(), properties.listingRetryDelayMs(), properties.skipListingIfPresent());

        // Skip the listing re-crawl when products are already persisted and the
        // option is enabled: re-crawling the listing burns DataDome's rate limit
        // and is pointless. Go straight to enriching the products that still
        // need their images or reviews fetched.
        if (properties.skipListingIfPresent() && productRepository.countAttractionProducts() > 0) {
            Map<String, AttractionProductListing> toEnrich = new java.util.LinkedHashMap<>();
            for (AttractionProductListing listing : productRepository.findAttractionProductsMissingImages()) {
                toEnrich.put(listing.url(), listing);
            }
            for (AttractionProductListing listing : productRepository.findAttractionProductsMissingReviews()) {
                toEnrich.putIfAbsent(listing.url(), listing);
            }
            List<AttractionProductListing> pending = new ArrayList<>(toEnrich.values());
            log.info("Tripadvisor attraction product listing skipped (skip-listing-if-present). existingAttractionProducts={}, productsToEnrich={}, baseUrl={}",
                    productRepository.countAttractionProducts(), pending.size(), properties.baseUrl());
            int detailed = crawlProductDetails(pending, null);
            log.info("Tripadvisor attraction product crawler manager finished (skip-listing mode). productsToEnrich={}, detailedProducts={}, totalAttractionProducts={}, elapsedMs={}",
                    pending.size(), detailed,
                    productRepository.countAttractionProducts(), System.currentTimeMillis() - startedAt);
            return;
        }

        crawlPaginated(startedAt);
    }

    /**
     * Paginated discovery (default): walks the listing pages by generating the
     * next {@code -oa{offset}-} URL, enriching each page's products, until
     * {@code max-empty-pages} consecutive empty/failed pages or {@code max-items}
     * is reached. The next page URL is persisted after each page so a restart
     * resumes from there.
     */
    private void crawlPaginated(long startedAt) {
        int offset = paginationGenerator.baseOffset();
        int consecutiveEmptyPages = 0;
        int submittedPages = 0;
        int completedPages = 0;
        int failedPages = 0;
        int extractedProducts = 0;
        int detailedProducts = 0;
        Set<String> seenProductUrls = new HashSet<>();

        while (consecutiveEmptyPages < properties.maxEmptyPages()
                && extractedProducts < properties.maxItems()) {
            String url = paginationGenerator.pageUrlForOffset(offset);
            submittedPages++;

            log.info("Tripadvisor attraction product listing page starting. offset={}, url={}", offset, url);
            AttractionProductCrawlResult listingResult = crawlListingPageWithRetry(url);

            if (listingResult == null || !listingResult.successful()) {
                failedPages++;
                consecutiveEmptyPages++;
                log.warn("Tripadvisor attraction product listing page failed. offset={}, url={}, error={}",
                        offset, url, listingResult == null ? "no attempt made" : listingResult.errorMessage());
                offset = paginationGenerator.nextOffset(offset);
                persistNextPageUrl(offset);
                continue;
            }

            completedPages++;
            List<AttractionProductListing> newProducts = listingResult.products().stream()
                    .filter(p -> seenProductUrls.add(p.url()))
                    .toList();
            extractedProducts += newProducts.size();
            log.info("Tripadvisor attraction product listing page completed. offset={}, url={}, products={}, newProducts={}, totalSeen={}, category='{}'",
                    offset, url, listingResult.productCount(), newProducts.size(),
                    seenProductUrls.size(), listingResult.categoryName());

            int pageDetailed = crawlProductDetails(newProducts, listingResult.categoryName());
            detailedProducts += pageDetailed;

            if (newProducts.isEmpty()) {
                consecutiveEmptyPages++;
            } else {
                consecutiveEmptyPages = 0;
            }

            log.info("Tripadvisor attraction product listing page finished. offset={}, url={}, products={}, newProducts={}, detailed={}, totalAttractionProducts={}",
                    offset, url, listingResult.productCount(), newProducts.size(), pageDetailed,
                    productRepository.countAttractionProducts());

            offset = paginationGenerator.nextOffset(offset);
            if (!newProducts.isEmpty()) {
                persistNextPageUrl(offset);
            } else {
                log.info("Tripadvisor attraction product listing stopped. reason='page returned no new products (unsupported -oa pagination or exhausted listing)' lastProductiveUrl={}, consecutiveEmptyPages={}, totalSeen={}",
                        url, consecutiveEmptyPages, seenProductUrls.size());
            }
        }

        log.info("Tripadvisor attraction product crawler manager finished. submittedPages={}, completedPages={}, failedPages={}, extractedProducts={}, detailedProducts={}, totalAttractionProducts={}, elapsedMs={}",
                submittedPages, completedPages, failedPages, extractedProducts, detailedProducts,
                productRepository.countAttractionProducts(), System.currentTimeMillis() - startedAt);
    }

    /**
     * Crawls a single listing page with the configured retry/backoff. Returns the
     * last attempt's result (failed when every attempt failed).
     */
    private AttractionProductCrawlResult crawlListingPageWithRetry(String url) {
        AttractionProductCrawlResult listingResult = null;
        for (int attempt = 1; attempt <= properties.listingMaxAttempts(); attempt++) {
            log.info("Tripadvisor attraction product listing page starting. attempt={}/{}, url={}",
                    attempt, properties.listingMaxAttempts(), url);
            listingResult = listingWorker.crawl(url);

            if (listingResult.successful()) {
                break;
            }

            log.warn("Tripadvisor attraction product listing page failed. attempt={}/{}, url={}, error={}",
                    attempt, properties.listingMaxAttempts(), url, listingResult.errorMessage());
            if (attempt < properties.listingMaxAttempts()) {
                log.info("Tripadvisor attraction product listing retry scheduled in {}ms (DataDome blocks are usually transient). attempt={}",
                        properties.listingRetryDelayMs(), attempt);
                try {
                    Thread.sleep(properties.listingRetryDelayMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("Tripadvisor attraction product crawler interrupted while waiting to retry listing page.");
                    return listingResult;
                }
            }
        }

        if (listingResult == null || !listingResult.successful()) {
            log.error("Tripadvisor attraction product listing page failed after {} attempts. url={}, error={}",
                    properties.listingMaxAttempts(), url,
                    listingResult == null ? "no attempt made" : listingResult.errorMessage());
            return listingResult;
        }

        log.info("Tripadvisor attraction product listing page completed. url={}, products={}, category='{}'",
                url, listingResult.productCount(), listingResult.categoryName());
        return listingResult;
    }

    /**
     * Persists the URL of the next page to process back into the
     * {@code application.properties} file so a restarted crawler resumes from
     * where this run left off. Non-fatal: failures are logged and swallowed.
     */
    private void persistNextPageUrl(int nextOffset) {
        String nextUrl = paginationGenerator.pageUrlForOffset(nextOffset);
        propertiesFileUpdater.updateBaseUrl(nextUrl);
    }

    private int crawlProductDetails(List<AttractionProductListing> products, String categoryName) {
        if (products.isEmpty()) {
            log.info("Tripadvisor attraction product detail stage skipped: no products extracted from listing page.");
            return 0;
        }
        long startedAt = System.currentTimeMillis();
        // A shared browser context is not thread-safe, so the detail stage is
        // always driven sequentially regardless of the configured concurrency.
        int concurrency = 1;

        List<AttractionProductListing> pending = new ArrayList<>();
        int alreadyProcessed = 0;
        for (AttractionProductListing product : products) {
            String key = normalizeProductKey(product.url());
            if (processedProductUrls.contains(key)) {
                alreadyProcessed++;
                log.info("Tripadvisor attraction product detail skipped (already processed this run). tripadvisorId={}, url={}",
                        product.tripadvisorId(), product.url());
            } else {
                pending.add(product);
            }
        }

        if (pending.isEmpty()) {
            log.info("Tripadvisor attraction product detail stage skipped: all {} products already processed this run.",
                    products.size());
            return 0;
        }

        log.info("Tripadvisor attraction product detail stage starting. products={}, pending={}, alreadyProcessed={}, concurrency={}",
                products.size(), pending.size(), alreadyProcessed, concurrency);

        int succeeded = 0;
        int failed = 0;
        int recycled = 0;
        // One shared browser/context for the whole detail stage: the DataDome
        // cookie established on the first product persists across all of them,
        // which is what avoids the recurring "access temporarily restricted"
        // challenge from opening a fresh browser per product.
        // Because the shared context is not thread-safe, the stage is driven
        // sequentially (concurrency is forced to 1). If the browser crashes
        // (TargetClosedError from missing Firefox media libs), it is recycled
        // so the next product gets a live context instead of failing instantly.
        AttractionProductDetailWorker.SharedBrowser shared = null;
        try {
            int index = 0;
            for (AttractionProductListing product : pending) {
                index++;
                if (shared == null) {
                    shared = detailWorker.openSharedBrowser();
                }
                AttractionProductDetailCrawlResult detailResult = detailWorker.crawl(product, shared.context(), categoryName);
                if (detailResult.successful()) {
                    succeeded++;
                    processedProductUrls.add(normalizeProductKey(detailResult.detail().url()));
                    log.info("Tripadvisor attraction product detail result {}/{}. tripadvisorId={}, url={}, status=SUCCESS, name='{}', rating={}, reviewCount={}, images={}",
                            index, pending.size(), detailResult.detail().tripadvisorId(), detailResult.detail().url(),
                            detailResult.detail().name(), detailResult.detail().rating(), detailResult.detail().reviewCount(),
                            detailResult.detail().imageUrls() == null ? 0 : detailResult.detail().imageUrls().size());
                } else {
                    failed++;
                    log.warn("Tripadvisor attraction product detail result {}/{}. status=FAILED, reason='{}'",
                            index, pending.size(), detailResult.errorMessage());
                    if (isBrowserCrash(detailResult.errorMessage())) {
                        closeSharedBrowser(shared);
                        shared = null;
                        recycled++;
                        log.info("Tripadvisor attraction product detail browser recycled after crash. recycled={}, index={}",
                                recycled, index);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Tripadvisor attraction product detail stage failed. succeeded={}, failed={}, error={}",
                    succeeded, failed, e.getMessage(), e);
            throw new IllegalStateException("Tripadvisor attraction product crawl interrupted or failed", e);
        } finally {
            closeSharedBrowser(shared);
        }

        log.info("Tripadvisor attraction product detail stage finished. products={}, pending={}, succeeded={}, failed={}, alreadyProcessed={}, recycled={}, successRate={}%, elapsedMs={}",
                products.size(), pending.size(), succeeded, failed, alreadyProcessed, recycled,
                pending.isEmpty() ? 0 : Math.round(100.0 * succeeded / pending.size()),
                System.currentTimeMillis() - startedAt);
        return succeeded;
    }

    private boolean isBrowserCrash(String errorMessage) {
        if (errorMessage == null) {
            return false;
        }
        String lower = errorMessage.toLowerCase();
        return errorMessage.contains("TargetClosedError")
                || errorMessage.contains("browser has been closed")
                || errorMessage.contains("DriverException")
                || lower.contains("window is null")
                || lower.contains("delayedstartuppromise")
                || lower.contains("browsercontext.newpage")
                || lower.contains("protocol error");
    }

    private void closeSharedBrowser(AttractionProductDetailWorker.SharedBrowser shared) {
        if (shared != null) {
            try {
                shared.close();
            } catch (Exception ignore) {
                // best-effort cleanup after a browser crash
            }
        }
    }

    private String normalizeProductKey(String url) {
        if (url == null) {
            return "";
        }
        int query = url.indexOf('?');
        String base = query >= 0 ? url.substring(0, query) : url;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }
}
