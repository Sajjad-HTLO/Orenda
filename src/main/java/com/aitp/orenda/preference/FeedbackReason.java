package com.aitp.orenda.preference;

/**
 * Optional reasons attached to a reaction. Most reasons carry a negative signal
 * (they describe why a suggestion didn't fit); {@code FIND_SIMILAR} instead asks
 * for more POIs like the current one.
 */
public enum FeedbackReason {
    TOO_EXPENSIVE,
    TOO_FAR,
    TOO_CROWDED,
    NOT_SUITABLE_FOR_KIDS,
    PREFER_QUIETER,
    FIND_SIMILAR
}