package com.aitp.orenda.trip;

import com.aitp.orenda.model.PoiResponse;
import com.aitp.orenda.repository.PoiRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TripRecommendationServiceTest {

    @Mock
    private PoiRepository poiRepository;

    @InjectMocks
    private TripRecommendationService service;

    private TripPlanRequest sampleRequest() {
        return TripPlanRequest.builder()
                .basics(TripPlanRequest.TripBasics.builder()
                        .destination("Istanbul")
                        .startDate(LocalDate.of(2026, 8, 15))
                        .endDate(LocalDate.of(2026, 8, 18))
                        .travelerCount(2)
                        .accommodationLocation("41.0082,28.9784")
                        .arrivalTime("14:30")
                        .departureTime("11:00")
                        .transportMode(TripEnums.TransportMode.DRIVING)
                        .build())
                .profile(TripPlanRequest.TravelerProfile.builder()
                        .ageRange(TripEnums.AgeRange.AGE_25_34)
                        .groupType(TripEnums.GroupType.COUPLE)
                        .mobilityLimitation(TripEnums.MobilityLimitation.NONE)
                        .build())
                .interests(TripPlanRequest.Interests.builder()
                        .selectedInterests(List.of(TripEnums.Interest.HISTORY, TripEnums.Interest.MUSEUMS))
                        .additionalNotes("Love hidden gems")
                        .build())
                .style(TripPlanRequest.TravelStyle.builder()
                        .pace(TripEnums.Pace.BALANCED)
                        .walking(TripEnums.WalkingLevel.MODERATE)
                        .budget(TripEnums.Budget.MID_RANGE)
                        .food(TripEnums.FoodPreference.LOCAL)
                        .planningStyle(TripEnums.PlanningStyle.RECOMMENDATIONS_ONLY)
                        .build())
                .build();
    }

    private PoiResponse museumPoi() {
        return PoiResponse.builder()
                .id("11111111-1111-1111-1111-111111111111")
                .nameTr("Topkapı Palace")
                .category("historic")
                .subcategory("palace")
                .lat(41.0117)
                .lon(28.9833)
                .completenessScore(85)
                .distanceKm(1.5)
                .attributes(Map.of("tourism", "museum", "wikidata", "Q201297"))
                .build();
    }

    private PoiResponse barPoi() {
        return PoiResponse.builder()
                .id("22222222-2222-2222-2222-222222222222")
                .nameTr("Nightclub X")
                .category("entertainment")
                .subcategory("nightclub")
                .lat(41.0200)
                .lon(28.9900)
                .completenessScore(70)
                .distanceKm(3.0)
                .attributes(Map.of())
                .build();
    }

    @Test
    void recommends_museums_over_nightclubs_for_history_lovers() {
        when(poiRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), isNull(), anyInt(), anyInt()))
                .thenReturn(List.of(museumPoi(), barPoi()));

        TripPlanResponse resp = service.recommend(sampleRequest());

        assertThat(resp.getTripDays()).isEqualTo(4);
        assertThat(resp.getSuggestions()).isNotEmpty();
        // Museum should rank first for a history/museum interest
        assertThat(resp.getSuggestions().get(0).getPoi().getId())
                .isEqualTo("11111111-1111-1111-1111-111111111111");
    }

    @Test
    void recommends_families_with_children_highly() {
        TripPlanRequest familyReq = TripPlanRequest.builder()
                .basics(sampleRequest().getBasics())
                .profile(TripPlanRequest.TravelerProfile.builder()
                        .ageRange(sampleRequest().getProfile().getAgeRange())
                        .groupType(TripEnums.GroupType.FAMILY)
                        .mobilityLimitation(sampleRequest().getProfile().getMobilityLimitation())
                        .build())
                .interests(TripPlanRequest.Interests.builder()
                        .selectedInterests(List.of(TripEnums.Interest.FAMILY_ACTIVITIES))
                        .additionalNotes(sampleRequest().getInterests().getAdditionalNotes())
                        .build())
                .style(sampleRequest().getStyle())
                .build();

        PoiResponse zoo = PoiResponse.builder()
                .id("33333333-3333-3333-3333-333333333333")
                .nameTr("Istanbul Zoo")
                .category("leisure")
                .subcategory("zoo")
                .lat(41.0100)
                .lon(28.9800)
                .completenessScore(80)
                .distanceKm(2.0)
                .attributes(Map.of())
                .build();

        when(poiRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), isNull(), anyInt(), anyInt()))
                .thenReturn(List.of(zoo, barPoi()));

        TripPlanResponse resp = service.recommend(familyReq);

        assertThat(resp.getSuggestions()).isNotEmpty();
        assertThat(resp.getSuggestions().get(0).getPoi().getId())
                .isEqualTo("33333333-3333-3333-3333-333333333333");
    }

    @Test
    void no_day_plan_when_recommendations_only() {
        when(poiRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), isNull(), anyInt(), anyInt()))
                .thenReturn(List.of(museumPoi()));

        TripPlanResponse resp = service.recommend(sampleRequest());

        assertThat(resp.getDayPlan()).isEmpty();
    }
}