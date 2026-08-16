package com.aitp.orenda.preference;

/**
 * Immediate reactions a traveler can give to a suggested POI. Each reaction
 * nudges the traveler's learned weight for that POI's preference category.
 */
public enum PreferenceReaction {
    LIKE,
    DISLIKE,
    LOVE,
    NOT_INTERESTED,
    RATED
}