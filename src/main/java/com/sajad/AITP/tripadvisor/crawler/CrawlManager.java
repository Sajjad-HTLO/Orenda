package com.sajad.AITP.tripadvisor.crawler;

import com.sajad.AITP.tripadvisor.config.TripadvisorCrawlerProperties;
import com.sajad.AITP.tripadvisor.model.CrawlPage;
import com.sajad.AITP.tripadvisor.model.ListingCrawlResult;
import com.sajad.AITP.tripadvisor.repository.HotelRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Slf4j
@Service
@ConditionalOnProperty(name = "tripadvisor.crawler.enabled", havingValue = "true")
public class CrawlManager {

    private final TripadvisorCrawlerProperties properties;
    private final PaginationGenerator paginationGenerator;
    private final ListingWorker listingWorker;
    private final HotelRepository hotelRepository;

    public CrawlManager(
            TripadvisorCrawlerProperties properties,
            PaginationGenerator paginationGenerator,
            ListingWorker listingWorker,
            HotelRepository hotelRepository) {
        this.properties = properties;
        this.paginationGenerator = paginationGenerator;
        this.listingWorker = listingWorker;
        this.hotelRepository = hotelRepository;
    }

    public void crawlListingPages() {
        int nextOffset = 0;
        int consecutiveEmptyPages = 0;
        int submittedPages = 0;
        int completedPages = 0;
        int skippedPages = 0;
        int failedPages = 0;
        int extractedHotels = 0;

        log.info("Tripadvisor crawl manager starting. baseUrl={}, concurrency={}, pageSize={}, stopAfterEmptyPages={}, singlePageOnly={}",
                properties.baseUrl(), properties.concurrency(), properties.pageSize(), properties.maxEmptyPages(), properties.singlePageOnly());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            int batchNumber = 1;
            while (consecutiveEmptyPages < properties.maxEmptyPages()) {
                List<CrawlPage> batch = properties.singlePageOnly()
                        ? List.of(CrawlPage.builder().offset(0).url(properties.baseUrl()).build())
                        : nextBatch(nextOffset);
                nextOffset = paginationGenerator.nextOffset(batch.get(batch.size() - 1).offset());
                submittedPages += batch.size();

                log.info("Tripadvisor batch {} starting. offsets={}, urls={}",
                        batchNumber,
                        batch.stream().map(CrawlPage::offset).toList(),
                        batch.stream().map(CrawlPage::url).toList());

                List<Future<ListingCrawlResult>> futures = batch.stream()
                        .map(page -> executor.submit(() -> listingWorker.crawl(page)))
                        .toList();

                boolean batchHadHotels = false;
                boolean batchHadCrawlFailure = false;
                for (Future<ListingCrawlResult> future : futures) {
                    ListingCrawlResult result = future.get();
                    log.info("Tripadvisor page result. offset={}, status={}, skipped={}, hotels={}, error={}",
                            result.offset(), result.successful() ? "SUCCESS" : "FAILED", result.skipped(), result.hotelCount(), result.errorMessage());
                    if (!result.successful()) {
                        failedPages++;
                        batchHadCrawlFailure = true;
                        continue;
                    }
                    if (result.skipped()) {
                        skippedPages++;
                    } else {
                        completedPages++;
                    }
                    extractedHotels += result.hotelCount();
                    if (result.hotelCount() > 0) {
                        batchHadHotels = true;
                    }
                }

                if (batchHadHotels) {
                    consecutiveEmptyPages = 0;
                } else {
                    consecutiveEmptyPages += batch.size();
                }

                log.info("Tripadvisor batch {} finished. submittedPages={}, completedPages={}, skippedPages={}, failedPages={}, extractedHotelsThisRun={}, totalTripadvisorPois={}, consecutiveEmptyPages={}, batchHadCrawlFailure={}",
                        batchNumber,
                        submittedPages,
                        completedPages,
                        skippedPages,
                        failedPages,
                        extractedHotels,
                        hotelRepository.countHotels(),
                        consecutiveEmptyPages,
                        batchHadCrawlFailure);
                if (properties.singlePageOnly()) {
                    log.info("Tripadvisor single-page-only mode enabled; stopping after first requested page.");
                    break;
                }
                batchNumber++;
            }
        } catch (Exception e) {
            Thread.currentThread().interrupt();
            log.error("Tripadvisor crawl manager failed. submittedPages={}, completedPages={}, skippedPages={}, failedPages={}, extractedHotelsThisRun={}, error={}",
                    submittedPages, completedPages, skippedPages, failedPages, extractedHotels, e.getMessage(), e);
            throw new IllegalStateException("Tripadvisor listing crawl interrupted or failed", e);
        }

        log.info("Tripadvisor crawl manager finished. submittedPages={}, completedPages={}, skippedPages={}, failedPages={}, extractedHotelsThisRun={}, totalTripadvisorPois={}",
                submittedPages, completedPages, skippedPages, failedPages, extractedHotels, hotelRepository.countHotels());
    }

    private List<CrawlPage> nextBatch(int startOffset) {
        List<CrawlPage> pages = new ArrayList<>();
        int offset = startOffset;
        for (int i = 0; i < properties.concurrency(); i++) {
            pages.add(paginationGenerator.pageForOffset(offset));
            offset = paginationGenerator.nextOffset(offset);
        }
        return pages;
    }
}
