package com.aitp.orenda.tripadvisor.restaurants;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

@Slf4j
@Service
@ConditionalOnProperty(name = "tripadvisor.crawler.restaurants.enabled", havingValue = "true")
public class RestaurantCrawlerManager {

    private final RestaurantCrawlerProperties properties;
    private final RestaurantPaginationGenerator paginationGenerator;
    private final RestaurantListingWorker listingWorker;
    private final RestaurantDetailWorker detailWorker;
    private final RestaurantRepository restaurantRepository;
    private final RestaurantPropertiesFileUpdater propertiesFileUpdater;

    private final Set<String> processedRestaurantUrls = ConcurrentHashMap.newKeySet();

    public RestaurantCrawlerManager(
            RestaurantCrawlerProperties properties,
            RestaurantPaginationGenerator paginationGenerator,
            RestaurantListingWorker listingWorker,
            RestaurantDetailWorker detailWorker,
            RestaurantRepository restaurantRepository,
            RestaurantPropertiesFileUpdater propertiesFileUpdater) {
        this.properties = properties;
        this.paginationGenerator = paginationGenerator;
        this.listingWorker = listingWorker;
        this.detailWorker = detailWorker;
        this.restaurantRepository = restaurantRepository;
        this.propertiesFileUpdater = propertiesFileUpdater;
    }

    /**
     * Crawls restaurant listing pages sequentially (offset 0 → 30 → 60 → …),
     * then crawls each restaurant's detail page to enrich it and download image
     * binaries. Pagination advances by the configured page size; the loop stops
     * after {@code max-empty-pages} consecutive empty/failed pages or when
     * single-page-only mode is enabled.
     */
    public void crawl() {
        long startedAt = System.currentTimeMillis();
        int offset = paginationGenerator.baseOffset();
        int consecutiveEmptyPages = 0;
        int submittedPages = 0;
        int completedPages = 0;
        int failedPages = 0;
        int extractedRestaurants = 0;
        int detailedRestaurants = 0;

        log.info("Tripadvisor restaurant crawler manager starting. baseUrl={}, baseOffset={}, pageSize={}, maxEmptyPages={}, singlePageOnly={}, headless={}",
                properties.baseUrl(), offset, properties.pageSize(), properties.maxEmptyPages(),
                properties.singlePageOnly(), properties.headless());

        while (consecutiveEmptyPages < properties.maxEmptyPages()) {
            String url = paginationGenerator.pageUrlForOffset(offset);
            submittedPages++;

            log.info("Tripadvisor restaurant listing page starting. offset={}, url={}", offset, url);
            RestaurantCrawlResult listingResult = listingWorker.crawl(url);

            if (!listingResult.successful()) {
                failedPages++;
                log.warn("Tripadvisor restaurant listing page failed. offset={}, url={}, error={}",
                        offset, url, listingResult.errorMessage());
                consecutiveEmptyPages++;
                offset = paginationGenerator.nextOffset(offset);
                persistNextPageUrl(offset);
                continue;
            }

            completedPages++;
            extractedRestaurants += listingResult.restaurantCount();
            log.info("Tripadvisor restaurant listing page completed. offset={}, url={}, restaurants={}",
                    offset, url, listingResult.restaurantCount());

            int pageDetailed = crawlRestaurantDetails(listingResult.restaurants());
            detailedRestaurants += pageDetailed;

            if (listingResult.restaurants().isEmpty()) {
                consecutiveEmptyPages++;
            } else {
                consecutiveEmptyPages = 0;
            }

            log.info("Tripadvisor restaurant listing page finished. offset={}, url={}, restaurants={}, detailed={}, totalTripadvisorRestaurants={}",
                    offset, url, listingResult.restaurantCount(), pageDetailed,
                    restaurantRepository.countRestaurants());

            if (properties.singlePageOnly()) {
                log.info("Tripadvisor restaurant single-page-only mode enabled; stopping after first requested page.");
                break;
            }
            offset = paginationGenerator.nextOffset(offset);
            persistNextPageUrl(offset);
        }

        log.info("Tripadvisor restaurant crawler manager finished. submittedPages={}, completedPages={}, failedPages={}, extractedRestaurants={}, detailedRestaurants={}, totalTripadvisorRestaurants={}, elapsedMs={}",
                submittedPages, completedPages, failedPages, extractedRestaurants, detailedRestaurants,
                restaurantRepository.countRestaurants(), System.currentTimeMillis() - startedAt);
    }

    private int crawlRestaurantDetails(List<RestaurantListing> restaurants) {
        if (restaurants.isEmpty()) {
            log.info("Tripadvisor restaurant detail stage skipped: no restaurants extracted from listing page.");
            return 0;
        }
        long startedAt = System.currentTimeMillis();
        int concurrency = Math.max(1, properties.concurrency());

        List<RestaurantListing> pending = new ArrayList<>();
        int alreadyProcessed = 0;
        for (RestaurantListing restaurant : restaurants) {
            String key = normalizeRestaurantKey(restaurant);
            if (processedRestaurantUrls.contains(key)) {
                alreadyProcessed++;
                log.info("Tripadvisor restaurant detail skipped (already processed this run). tripadvisorId={}, url={}",
                        restaurant.tripadvisorId(), restaurant.url());
            } else {
                pending.add(restaurant);
            }
        }

        if (pending.isEmpty()) {
            log.info("Tripadvisor restaurant detail stage skipped: all {} restaurants already processed this run. alreadyProcessed={}",
                    restaurants.size(), alreadyProcessed);
            return 0;
        }

        log.info("Tripadvisor restaurant detail stage starting. restaurants={}, pending={}, alreadyProcessed={}, concurrency={}",
                restaurants.size(), pending.size(), alreadyProcessed, concurrency);

        int succeeded = 0;
        int failed = 0;
        Semaphore semaphore = new Semaphore(concurrency);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<RestaurantDetailCrawlResult>> futures = pending.stream()
                    .map(restaurant -> executor.submit(() -> {
                        semaphore.acquire();
                        try {
                            return detailWorker.crawl(restaurant);
                        } finally {
                            semaphore.release();
                        }
                    }))
                    .toList();

            for (Future<RestaurantDetailCrawlResult> future : futures) {
                RestaurantDetailCrawlResult detailResult = future.get();
                if (detailResult.successful()) {
                    succeeded++;
                    processedRestaurantUrls.add(normalizeRestaurantKey(detailResult.detail().url()));
                    log.info("Tripadvisor restaurant detail result. tripadvisorId={}, url={}, status=SUCCESS, name='{}', lat={}, lon={}, rating={}, reviewCount={}, images={}",
                            detailResult.detail().tripadvisorId(), detailResult.detail().url(), detailResult.detail().name(),
                            detailResult.detail().latitude(), detailResult.detail().longitude(),
                            detailResult.detail().rating(), detailResult.detail().reviewCount(),
                            detailResult.detail().imageUrls() == null ? 0 : detailResult.detail().imageUrls().size());
                } else {
                    failed++;
                    log.warn("Tripadvisor restaurant detail result. status=FAILED, reason='{}'", detailResult.errorMessage());
                }
            }
        } catch (Exception e) {
            Thread.currentThread().interrupt();
            log.error("Tripadvisor restaurant detail stage failed. succeeded={}, failed={}, error={}",
                    succeeded, failed, e.getMessage(), e);
            throw new IllegalStateException("Tripadvisor restaurant detail crawl interrupted or failed", e);
        }

        log.info("Tripadvisor restaurant detail stage finished. restaurants={}, pending={}, succeeded={}, failed={}, alreadyProcessed={}, successRate={}%, elapsedMs={}",
                restaurants.size(), pending.size(), succeeded, failed, alreadyProcessed,
                pending.isEmpty() ? 0 : Math.round(100.0 * succeeded / pending.size()),
                System.currentTimeMillis() - startedAt);
        return succeeded;
    }

    private String normalizeRestaurantKey(RestaurantListing restaurant) {
        return normalizeRestaurantKey(restaurant.url());
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

    private String normalizeRestaurantKey(String url) {
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