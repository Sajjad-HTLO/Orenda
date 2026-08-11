package com.aitp.orenda.tripadvisor.repository;

/**
 * Schema for Tripadvisor crawl progress is managed by Flyway migration
 * V8__allow_tripadvisor_osm_type_and_progress.sql. Hotel data is stored in
 * the shared poi table, not in a crawler-specific SQLite database.
 */
public final class TripadvisorSchemaInitializer {

    private TripadvisorSchemaInitializer() {
    }
}
