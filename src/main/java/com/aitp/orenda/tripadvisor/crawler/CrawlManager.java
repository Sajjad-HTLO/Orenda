package com.aitp.orenda.tripadvisor.crawler;

import com.aitp.orenda.tripadvisor.config.TripadvisorCrawlerProperties;
import com.aitp.orenda.tripadvisor.model.CrawlPage;
import com.aitp.orenda.tripadvisor.model.HotelDetailCrawlResult;
import com.aitp.orenda.tripadvisor.model.HotelListing;
import com.aitp.orenda.tripadvisor.model.ListingCrawlResult;
import com.aitp.orenda.tripadvisor.repository.HotelRepository;
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
@ConditionalOnProperty(name = "tripadvisor.crawler.enabled", havingValue = "true")
public class CrawlManager {

    private final TripadvisorCrawlerProperties properties;
    private final PaginationGenerator paginationGenerator;
    private final ListingWorker listingWorker;
    private final HotelDetailWorker hotelDetailWorker;
    private final HotelRepository hotelRepository;

    /**
     * In-memory registry of hotel detail URLs that have already been successfully
     * crawled and persisted during this run. Prevents the same hotel from being
     * re-processed if it appears on multiple listing pages (e.g. sponsored or
     * cross-listed hotels), avoiding duplicate work and redundant browser launches.
     * <p>
     * Keyed by the normalized hotel detail URL. Thread-safe via
     * {@link ConcurrentHashMap#newKeySet()}.
     */
    private final Set<String> processedHotelUrls = ConcurrentHashMap.newKeySet();

    public CrawlManager(
            TripadvisorCrawlerProperties properties,
            PaginationGenerator paginationGenerator,
            ListingWorker listingWorker,
            HotelDetailWorker hotelDetailWorker,
            HotelRepository hotelRepository) {
        this.properties = properties;
        this.paginationGenerator = paginationGenerator;
        this.listingWorker = listingWorker;
        this.hotelDetailWorker = hotelDetailWorker;
        this.hotelRepository = hotelRepository;
    }

    /**
     * Crawls listing pages sequentially. For each listing page, the page is
     * crawled to extract its ~30 hotel URLs, then every hotel detail page is
     * fully crawled and persisted before advancing to the next listing page.
     * <p>
     * Pagination advances by {@code pageSize} (30) from the offset embedded in
     * the base URL: oa90 → oa120 → oa150 → oa180 → ...
     */
    public void crawlListingPages() {
        int offset = paginationGenerator.baseOffset();
        int consecutiveEmptyPages = 0;
        int submittedPages = 0;
        int completedPages = 0;
        int skippedPages = 0;
        int failedPages = 0;
        int extractedHotels = 0;
        int detailedHotels = 0;

        log.info("Tripadvisor crawl manager starting. baseUrl={}, baseOffset={}, concurrency={}, pageSize={}, stopAfterEmptyPages={}, singlePageOnly={}",
                properties.baseUrl(), offset, properties.concurrency(), properties.pageSize(),
                properties.maxEmptyPages(), properties.singlePageOnly());

        while (consecutiveEmptyPages < properties.maxEmptyPages()) {
            CrawlPage page = paginationGenerator.pageForOffset(offset);
            submittedPages++;

            log.info("Tripadvisor listing page starting. offset={}, url={}", page.offset(), page.url());
            ListingCrawlResult listingResult = listingWorker.crawl(page);

            if (!listingResult.successful()) {
                failedPages++;
                log.warn("Tripadvisor listing page failed. offset={}, url={}, error={}",
                        page.offset(), page.url(), listingResult.errorMessage());
                consecutiveEmptyPages++;
                offset = paginationGenerator.nextOffset(offset);
                continue;
            }
            if (listingResult.skipped()) {
                skippedPages++;
                log.info("Tripadvisor listing page skipped (already completed). offset={}, url={}",
                        page.offset(), page.url());
                consecutiveEmptyPages++;
                offset = paginationGenerator.nextOffset(offset);
                continue;
            }

            completedPages++;
            extractedHotels += listingResult.hotelCount();
            List<HotelListing> pageHotels = listingResult.hotels();
            log.info("Tripadvisor listing page completed. offset={}, url={}, hotels={}",
                    page.offset(), page.url(), pageHotels.size());

            // Fully crawl every hotel detail on this page before moving on.
            int pageDetailed = crawlHotelDetails(pageHotels);
            detailedHotels += pageDetailed;

            if (pageHotels.isEmpty()) {
                consecutiveEmptyPages++;
            } else {
                consecutiveEmptyPages = 0;
            }

            log.info("Tripadvisor listing page finished. offset={}, url={}, hotels={}, detailed={}, totalTripadvisorPois={}",
                    page.offset(), page.url(), pageHotels.size(), pageDetailed, hotelRepository.countHotels());

            if (properties.singlePageOnly()) {
                log.info("Tripadvisor single-page-only mode enabled; stopping after first requested page.");
                break;
            }
            offset = paginationGenerator.nextOffset(offset);
        }

        log.info("Tripadvisor crawl manager finished. submittedPages={}, completedPages={}, skippedPages={}, failedPages={}, extractedHotels={}, detailedHotels={}, totalTripadvisorPois={}",
                submittedPages, completedPages, skippedPages, failedPages, extractedHotels, detailedHotels,
                hotelRepository.countHotels());
    }

    /**
     * Stage 2: opens each hotel detail URL, extracts detailed data, maps it onto
     * the shared {@code poi} model and persists it.
     * <p>
     * Concurrency is bounded by {@code properties.concurrency()} using a
     * semaphore so we never launch more Chrome instances than configured. Each
     * hotel opens its own Playwright/browser instance, so launching all hotels
     * at once would exhaust system resources and trigger DataDome blocking.
     *
     * @return the number of hotels successfully detailed and persisted.
     */
    private int crawlHotelDetails(List<HotelListing> hotels) {
        if (hotels.isEmpty()) {
            log.info("Tripadvisor hotel detail stage skipped: no hotels extracted from listing page.");
            return 0;
        }
        long startedAt = System.currentTimeMillis();
        int concurrency = Math.max(1, properties.concurrency());

        // Deduplicate: skip hotels whose detail URL was already successfully
        // processed earlier in this run (e.g. cross-listed on multiple pages).
        List<HotelListing> pending = new ArrayList<>();
        int alreadyProcessed = 0;
        for (HotelListing hotel : hotels) {
            String key = normalizeHotelKey(hotel);
            if (processedHotelUrls.contains(key)) {
                alreadyProcessed++;
                log.info("Tripadvisor hotel detail skipped (already processed this run). tripadvisorId={}, url={}",
                        hotel.tripadvisorId(), hotel.url());
            } else {
                pending.add(hotel);
            }
        }

        if (pending.isEmpty()) {
            log.info("Tripadvisor hotel detail stage skipped: all {} hotels already processed this run. alreadyProcessed={}",
                    hotels.size(), alreadyProcessed);
            return 0;
        }

        log.info("Tripadvisor hotel detail stage starting. hotels={}, pending={}, alreadyProcessed={}, concurrency={}",
                hotels.size(), pending.size(), alreadyProcessed, concurrency);

        int succeeded = 0;
        int failed = 0;
        Semaphore semaphore = new Semaphore(concurrency);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<HotelDetailCrawlResult>> futures = pending.stream()
                    .map(hotel -> executor.submit(() -> {
                        semaphore.acquire();
                        try {
                            return hotelDetailWorker.crawl(hotel);
                        } finally {
                            semaphore.release();
                        }
                    }))
                    .toList();

            for (Future<HotelDetailCrawlResult> future : futures) {
                HotelDetailCrawlResult result = future.get();
                if (result.successful()) {
                    succeeded++;
                    processedHotelUrls.add(normalizeHotelKey(result.detail().url()));
                    log.info("Tripadvisor hotel detail result. tripadvisorId={}, url={}, status=SUCCESS, name='{}', lat={}, lon={}, rating={}, reviewCount={}, images={}",
                            result.detail().tripadvisorId(), result.detail().url(), result.detail().name(),
                            result.detail().latitude(), result.detail().longitude(),
                            result.detail().rating(), result.detail().reviewCount(),
                            result.detail().imageUrls() == null ? 0 : result.detail().imageUrls().size());
                } else {
                    failed++;
                    log.warn("Tripadvisor hotel detail result. status=FAILED, reason='{}'", result.errorMessage());
                }
            }
        } catch (Exception e) {
            Thread.currentThread().interrupt();
            log.error("Tripadvisor hotel detail stage failed. succeeded={}, failed={}, error={}",
                    succeeded, failed, e.getMessage(), e);
            throw new IllegalStateException("Tripadvisor hotel detail crawl interrupted or failed", e);
        }

        log.info("Tripadvisor hotel detail stage finished. hotels={}, pending={}, succeeded={}, failed={}, alreadyProcessed={}, successRate={}%, totalTripadvisorPois={}, elapsedMs={}",
                hotels.size(), pending.size(), succeeded, failed, alreadyProcessed,
                pending.isEmpty() ? 0 : Math.round(100.0 * succeeded / pending.size()),
                hotelRepository.countHotels(), System.currentTimeMillis() - startedAt);
        return succeeded;
    }

    /**
     * Normalizes a hotel detail URL into a stable deduplication key. Strips the
     * query string and trailing slash so equivalent URLs (e.g. with different
     * tracking params) collapse to the same key.
     */
    private String normalizeHotelKey(String url) {
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

    private String normalizeHotelKey(HotelListing hotel) {
        return normalizeHotelKey(hotel.url());
    }
}
