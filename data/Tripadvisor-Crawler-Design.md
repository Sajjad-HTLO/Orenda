# Tripadvisor Crawler Project Specification

## Goal

Build a production-quality Java crawler for Tripadvisor hotel listings,
starting with Istanbul.

## Technology Stack

-   Java 21
-   Spring Boot (CLI application using CommandLineRunner)
-   Playwright for Java
-   Jsoup
-   SQLite (easy migration to PostgreSQL later)
-   Spring JDBC
-   Virtual Threads
-   Maven
-   SLF4J + Logback
-   Lombok

## Initial Requirements

-   Crawl listing pages only.
-   Generate pagination URLs instead of clicking "Next".
-   Concurrency = 2 workers.
-   Use random delays between requests.
-   Parse rendered HTML using Jsoup.
-   Persist crawl progress and hotel URLs in SQLite.
-   Resume after interruption.
-   Avoid duplicate hotel entries.

## Pagination

Base URL:

https://www.tripadvisor.com/Hotels-g293974-Istanbul-Hotels.html

Pattern:

-   offset 0:
    https://www.tripadvisor.com/Hotels-g293974-Istanbul-Hotels.html
-   offset 30:
    https://www.tripadvisor.com/Hotels-g293974-oa30-Istanbul-Hotels.html
-   offset 60:
    https://www.tripadvisor.com/Hotels-g293974-oa60-Istanbul-Hotels.html

Increase the offset by 30 until no hotel cards are found.

## Architecture

Stage 1: Listing Pages → Extract hotel URLs → Store in SQLite

Stage 2: Hotel URLs → Visit each hotel page → Extract hotel details →
Store in SQLite

This two-stage architecture enables independent retries, resumability,
and future scaling.

## Project Structure

``` text
tripadvisor-crawler/
├── pom.xml
├── src/main/java/com/example/tripadvisor
│   ├── config
│   ├── crawler
│   ├── parser
│   ├── repository
│   ├── model
│   ├── util
│   └── Application.java
├── src/main/resources
│   ├── application.yml
│   └── schema.sql
└── README.md
```

## Main Components

-   CrawlManager
-   PaginationGenerator
-   ListingWorker
-   HotelWorker
-   ListingParser
-   HotelParser
-   HotelRepository
-   PageRepository
-   PlaywrightConfig
-   DatabaseConfig

## Database

Tables:

-   hotels
-   crawled_pages

Future additions:

-   hotel_details
-   crawl_errors

## Concurrency

-   Executor: Java 21 Virtual Threads
-   Active workers: 2
-   One Playwright BrowserContext per worker

## Reliability

-   Resume support
-   Retry failed pages
-   Logging
-   Duplicate detection
-   Headless execution
-   Configurable delays
-   Configurable concurrency

## Future Enhancements

-   PostgreSQL support
-   CSV export
-   Docker image
-   Hotel image extraction
-   Amenities
-   Reviews
-   Coordinates
-   Scheduled crawling
-   Incremental refresh

## Implementation Plan

1.  Maven project
2.  Dependencies
3.  Spring Boot bootstrap
4.  SQLite schema
5.  Repository layer
6.  Playwright configuration
7.  Pagination generator
8.  Listing parser
9.  Listing crawler
10. Crawl manager
11. Resume mechanism
12. Hotel detail crawler
13. CSV export
14. Docker support
15. Documentation

## Notes

The crawler should be designed as a production-quality, modular
application rather than a one-off script, emphasizing maintainability,
extensibility, and robustness.
