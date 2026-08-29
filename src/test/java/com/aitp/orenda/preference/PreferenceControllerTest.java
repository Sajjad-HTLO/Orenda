package com.aitp.orenda.preference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP surface of the preference endpoints. Standalone MockMvc keeps this a
 * fast unit test (no Spring Boot context).
 */
class PreferenceControllerTest {

    private MockMvc mockMvc;
    private PreferenceService preferenceService;
    private JobLauncher jobLauncher;
    private ObjectProvider<Job> jobProvider;

    @BeforeEach
    void setUp() {
        preferenceService = mock(PreferenceService.class);
        jobLauncher = mock(JobLauncher.class);
        jobProvider = mock(ObjectProvider.class);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new PreferenceController(preferenceService, jobLauncher, jobProvider))
                .setValidator(validator)
                .build();
    }

    @Test
    void weights_returns_the_sessions_overview() throws Exception {
        when(preferenceService.overviewWeights("session-1")).thenReturn(Map.of("CULTURE", 0.8, "FOOD", 0.6));

        mockMvc.perform(get("/api/preferences/session-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.CULTURE").value(0.8))
                .andExpect(jsonPath("$.FOOD").value(0.6));
    }

    @Test
    void feedback_accepts_a_valid_reaction() throws Exception {
        when(preferenceService.processFeedback(any()))
                .thenReturn(PreferenceFeedbackResponse.builder()
                        .accepted(true)
                        .message("Noted")
                        .updatedWeights(Map.of("CULTURE", 0.9))
                        .build());

        mockMvc.perform(post("/api/preferences/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "poiId": "11111111-1111-1111-1111-111111111111",
                                  "sessionId": "session-1",
                                  "reaction": "LOVE"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.updatedWeights.CULTURE").value(0.9));
    }

    @Test
    void feedback_missing_poiId_is_rejected() throws Exception {
        mockMvc.perform(post("/api/preferences/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId": "session-1",
                                  "reaction": "LOVE"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void feedback_bad_rating_is_rejected() throws Exception {
        mockMvc.perform(post("/api/preferences/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "poiId": "11111111-1111-1111-1111-111111111111",
                                  "sessionId": "session-1",
                                  "reaction": "RATED",
                                  "rating": 9
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void enrich_launches_the_job_when_available() throws Exception {
        Job job = mock(Job.class);
        when(jobProvider.getIfAvailable()).thenReturn(job);
        JobExecution execution = mock(JobExecution.class);
        when(execution.getStatus()).thenReturn(BatchStatus.STARTED);
        when(jobLauncher.run(eq(job), any(JobParameters.class))).thenReturn(execution);

        mockMvc.perform(post("/api/preferences/session-1/enrich"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.status").value("STARTED"));
    }

    @Test
    void enrich_returns_503_when_job_disabled() throws Exception {
        when(jobProvider.getIfAvailable()).thenReturn(null);

        mockMvc.perform(post("/api/preferences/session-1/enrich"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.accepted").value(false));
    }
}