package com.aitp.orenda.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot 4 auto-configures Jackson 3 (tools.jackson) only; components that
 * inject the Jackson 2 {@link ObjectMapper} (e.g. SavedTripRepository) need an
 * explicit bean since the Jackson 2 auto-configuration was removed.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper jackson2ObjectMapper() {
        return new ObjectMapper();
    }
}
