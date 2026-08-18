package com.aitp.orenda.trip;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP surface of the trip-planning endpoint: request validation and the
 * contract of {@code POST /api/trips/plan}. Standalone MockMvc keeps this a
 * fast, focused unit test (no Spring Boot context required).
 */
class TripPlannerControllerTest {

    private MockMvc mockMvc;
    private TripRecommendationService recommendationService;

    @BeforeEach
    void setUp() {
        recommendationService = mock(TripRecommendationService.class);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TripPlannerController(recommendationService))
                .setValidator(validator)
                .build();
    }

    private static final String VALID_BODY = """
            {
              "basics": {
                "destination": "Istanbul",
                "startDate": "2026-08-15",
                "endDate": "2026-08-18",
                "travelerCount": 2,
                "accommodationLocation": "41.0082,28.9784",
                "transportMode": "FOOT"
              },
              "profile": {
                "ageRange": "AGE_25_34",
                "groupType": "COUPLE",
                "mobilityLimitation": "NONE",
                "diet": "VEGETARIAN"
              },
              "interests": {
                "selectedInterests": ["HISTORY", "MUSEUMS", "FOOD"]
              },
              "style": {
                "pace": "BALANCED",
                "walking": "MODERATE",
                "budget": "MID_RANGE",
                "food": "LOCAL",
                "planningStyle": "DETAILED_SCHEDULE"
              }
            }
            """;

    @Test
    void planTrip_returns_the_generated_plan() throws Exception {
        TripPlanResponse response = TripPlanResponse.builder()
                .tripDays(4)
                .summary("Found 20 suggestions for your 4-day couple trip to Istanbul.")
                .weatherSummary("AUG 15: Clear sky, 30°C")
                .suggestions(List.of(TripPlanResponse.ScoredPoi.builder()
                        .score(87.3)
                        .reasons(List.of("Matches your interest in history"))
                        .build()))
                .dayPlan(List.of(TripPlanResponse.DayPlan.builder()
                        .day(1)
                        .date("2026-08-15")
                        .weather("Clear sky, 30°C")
                        .items(List.of())
                        .build()))
                .build();
        when(recommendationService.recommend(any())).thenReturn(response);

        mockMvc.perform(post("/api/trips/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tripDays").value(4))
                .andExpect(jsonPath("$.summary").value(containsString("Istanbul")))
                .andExpect(jsonPath("$.weatherSummary").value("AUG 15: Clear sky, 30°C"))
                .andExpect(jsonPath("$.suggestions[0].score").value(87.3))
                .andExpect(jsonPath("$.suggestions[0].reasons[0]")
                        .value("Matches your interest in history"))
                .andExpect(jsonPath("$.dayPlan[0].day").value(1));
    }

    @Test
    void planTrip_accepts_recommendations_only_style() throws Exception {
        when(recommendationService.recommend(any())).thenReturn(TripPlanResponse.builder()
                .tripDays(4).build());

        String body = VALID_BODY.replace("DETAILED_SCHEDULE", "RECOMMENDATIONS_ONLY");

        mockMvc.perform(post("/api/trips/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    @Test
    void planTrip_rejects_a_request_without_basics() throws Exception {
        mockMvc.perform(post("/api/trips/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "profile": {
                                    "ageRange": "AGE_25_34",
                                    "groupType": "COUPLE",
                                    "mobilityLimitation": "NONE"
                                  },
                                  "interests": { "selectedInterests": ["HISTORY"] },
                                  "style": {
                                    "pace": "BALANCED",
                                    "walking": "MODERATE",
                                    "budget": "MID_RANGE",
                                    "food": "LOCAL",
                                    "planningStyle": "DETAILED_SCHEDULE"
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void planTrip_rejects_an_unknown_transport_mode() throws Exception {
        mockMvc.perform(post("/api/trips/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"FOOT\"", "\"TELEPORT\"")))
                .andExpect(status().isBadRequest());
    }
}
