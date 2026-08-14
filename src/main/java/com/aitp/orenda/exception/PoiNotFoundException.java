package com.aitp.orenda.exception;

/**
 * Thrown when a feedback request references a POI id that does not exist.
 */
public class PoiNotFoundException extends RuntimeException {

    public PoiNotFoundException(String poiId) {
        super("POI not found: " + poiId);
    }
}
