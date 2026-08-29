package com.aitp.orenda.preference;

import com.aitp.orenda.trip.TripEnums;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP surface of the onboarding profile endpoints. Standalone MockMvc keeps
 * this a fast unit test (no Spring Boot context).
 */
class ProfileControllerTest {

    private MockMvc mockMvc;
    private PreferenceService preferenceService;

    @BeforeEach
    void setUp() {
        preferenceService = mock(PreferenceService.class);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new ProfileController(preferenceService))
                .setValidator(validator)
                .build();
    }

    private TravelerProfileResponse sampleProfile() {
        return TravelerProfileResponse.builder()
                .sessionId("session-1")
                .travelerCount(2)
                .childrenCount(1)
                .interests(List.of(TripEnums.Interest.HISTORY, TripEnums.Interest.FOOD))
                .groupType(TripEnums.GroupType.FAMILY)
                .ageRange(TripEnums.AgeRange.AGE_25_34)
                .budget(TripEnums.Budget.MID_RANGE)
                .build();
    }

    @Test
    void upsert_accepts_valid_profile_and_returns_it() throws Exception {
        when(preferenceService.upsertProfile(any())).thenReturn(sampleProfile());

        mockMvc.perform(post("/api/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId": "session-1",
                                  "travelerCount": 2,
                                  "childrenCount": 1,
                                  "interests": ["HISTORY", "FOOD"],
                                  "groupType": "FAMILY",
                                  "ageRange": "AGE_25_34",
                                  "budget": "MID_RANGE"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("session-1"))
                .andExpect(jsonPath("$.groupType").value("FAMILY"));
    }

    @Test
    void upsert_without_sessionId_is_rejected() throws Exception {
        mockMvc.perform(post("/api/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "travelerCount": 2
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void get_returns_profile_for_existing_session() throws Exception {
        when(preferenceService.getProfile("session-1")).thenReturn(sampleProfile());

        mockMvc.perform(get("/api/profile/session-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("session-1"));
    }

    @Test
    void get_returns_404_for_unknown_session() throws Exception {
        when(preferenceService.getProfile("nobody")).thenReturn(null);

        mockMvc.perform(get("/api/profile/nobody"))
                .andExpect(status().isNotFound());
    }
}