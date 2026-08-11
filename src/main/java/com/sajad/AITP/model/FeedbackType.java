package com.sajad.AITP.model;

/**
 * Rich set of POI feedback types users can report.
 */
public enum FeedbackType {
    /**
     * POI is closed (permanently or temporarily). Excluded from future results.
     */
    CLOSED,
    /**
     * POI data is inaccurate (wrong name, hours, phone, etc.). Flagged for review.
     */
    INACCURATE,
    /**
     * POI location is wrong. Flagged for review; corrected coordinates accepted.
     */
    MOVED,
    /**
     * Duplicate POI entry. Excluded from future results.
     */
    DUPLICATE,
    /**
     * Catch-all for other issues (free-text). Flagged for review.
     */
    OTHER
}
