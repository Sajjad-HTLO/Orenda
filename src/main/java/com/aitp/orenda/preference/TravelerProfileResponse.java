package com.aitp.orenda.preference;

import com.aitp.orenda.trip.TripEnums;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The stored long-term traveler profile, returned to clients so they can render
 * and edit it.
 */
@Data
@Builder
public class TravelerProfileResponse {

    private String sessionId;

    private Integer travelerCount;

    private Integer childrenCount;

    private List<TripEnums.Interest> interests;

    private TripEnums.GroupType groupType;

    private TripEnums.AgeRange ageRange;

    private TripEnums.MobilityLimitation mobility;

    private TripEnums.Pace pace;

    private TripEnums.Budget budget;

    private TripEnums.WalkingLevel walking;

    private TripEnums.FoodPreference food;

    private OffsetDateTime updatedAt;
}