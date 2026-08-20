package com.aitp.orenda.preference;

import com.aitp.orenda.model.PoiResponse;
import com.aitp.orenda.repository.PoiRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PreferenceServiceTest {

    private static final String POI_ID = "11111111-1111-1111-1111-111111111111";

    @Mock
    private PreferenceRepository preferenceRepository;

    @Mock
    private PoiRepository poiRepository;

    @InjectMocks
    private PreferenceService service;

    private PoiResponse museum() {
        return PoiResponse.builder()
                .id(POI_ID)
                .nameTr("Topkapı Palace")
                .category("historic")
                .subcategory("palace")
                .lat(41.0117)
                .lon(28.9833)
                .completenessScore(85)
                .attributes(Map.of())
                .build();
    }

    private PreferenceFeedbackRequest request(PreferenceReaction reaction, FeedbackReason reason) {
        return PreferenceFeedbackRequest.builder()
                .poiId(POI_ID)
                .sessionId("s1")
                .reaction(reaction)
                .reason(reason)
                .build();
    }

    @Test
    void love_reaction_raises_weight_for_poi_category() {
        when(poiRepository.findById(POI_ID)).thenReturn(Optional.of(museum()));
        when(preferenceRepository.loadWeight(anyString(), anyString())).thenReturn(0.5);

        service.processFeedback(request(PreferenceReaction.LOVE, null));

        // LOVE → target 1.0, EMA from 0.5 with α=0.2 → 0.6
        ArgumentCaptor<Double> weight = ArgumentCaptor.forClass(Double.class);
        verify(preferenceRepository).recordFeedback(eq("s1"), eq(POI_ID), eq(PreferenceReaction.LOVE),
                isNull(), isNull(), eq("CULTURE"), weight.capture());
        assertThat(weight.getValue()).isEqualTo(0.6);
    }

    @Test
    void not_interested_with_reason_lowers_weight() {
        when(poiRepository.findById(POI_ID)).thenReturn(Optional.of(museum()));
        when(preferenceRepository.loadWeight(anyString(), anyString())).thenReturn(0.5);

        service.processFeedback(request(PreferenceReaction.NOT_INTERESTED, FeedbackReason.NOT_SUITABLE_FOR_KIDS));

        // NOT_INTERESTED → -1.0, minus 0.3 for reason = -1.3 → target clamped to 0.0;
        // 0.5 + 0.2*(0.0-0.5) = 0.37
        ArgumentCaptor<Double> weight = ArgumentCaptor.forClass(Double.class);
        verify(preferenceRepository).recordFeedback(eq("s1"), eq(POI_ID), eq(PreferenceReaction.NOT_INTERESTED),
                isNull(), eq(FeedbackReason.NOT_SUITABLE_FOR_KIDS), eq("CULTURE"), weight.capture());
        assertThat(weight.getValue()).isEqualTo(0.37);
    }

    @Test
    void find_similar_returns_same_category_pois() {
        when(poiRepository.findById(POI_ID)).thenReturn(Optional.of(museum()));
        when(poiRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), eq("historic"), anyInt(), anyInt()))
                .thenReturn(List.of(museum()));
        when(preferenceRepository.loadWeight(anyString(), anyString())).thenReturn(0.5);

        PreferenceFeedbackResponse resp = service.processFeedback(
                request(PreferenceReaction.LIKE, FeedbackReason.FIND_SIMILAR));

        assertThat(resp.getSimilarPois()).isNotEmpty();
    }

    @Test
    void too_expensive_derives_budget_cap_constraint() {
        when(poiRepository.findById(POI_ID)).thenReturn(Optional.of(museum()));
        when(preferenceRepository.loadWeight(anyString(), anyString())).thenReturn(0.5);

        service.processFeedback(request(PreferenceReaction.DISLIKE, FeedbackReason.TOO_EXPENSIVE));

        // no prior cap → start from LUXURY, lower one tier → PREMIUM
        verify(preferenceRepository).upsertConstraint(eq("s1"), eq("BUDGET_CAP"), eq("PREMIUM"));
    }

    @Test
    void too_far_derives_radius_constraint() {
        when(poiRepository.findById(POI_ID)).thenReturn(Optional.of(museum()));
        when(preferenceRepository.loadWeight(anyString(), anyString())).thenReturn(0.5);

        service.processFeedback(request(PreferenceReaction.DISLIKE, FeedbackReason.TOO_FAR));

        verify(preferenceRepository).upsertConstraint(eq("s1"), eq("MAX_RADIUS_KM"), eq("8.0"));
    }

    @Test
    void too_crowded_derives_flag_constraint() {
        when(poiRepository.findById(POI_ID)).thenReturn(Optional.of(museum()));
        when(preferenceRepository.loadWeight(anyString(), anyString())).thenReturn(0.5);

        service.processFeedback(request(PreferenceReaction.DISLIKE, FeedbackReason.TOO_CROWDED));

        verify(preferenceRepository).upsertConstraint(eq("s1"), eq("AVOID_CROWDED"), eq("true"));
    }

    @Test
    void insight_surfaces_preferences_and_avoidances() {
        Map<String, Double> weights = Map.of(
                "CULTURE", 0.91, "FOOD", 0.83, "SHOPPING", 0.21, "NIGHTLIFE", 0.08);

        String insight = service.insightFor(weights);

        assertThat(insight).contains("cultural experiences");
        assertThat(insight).contains("local food");
        assertThat(insight).contains("nightlife");
        assertThat(insight).contains("shopping");
        assertThat(insight).contains("I've adjusted your recommendations accordingly.");
    }

    @Test
    void no_insight_when_weights_are_flat() {
        assertThat(service.insightFor(Map.of("CULTURE", 0.5, "FOOD", 0.5))).isNull();
        assertThat(service.insightFor(Map.of())).isNull();
    }
}