package com.aitp.orenda.weather;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenMeteoResponse(
        double latitude,
        double longitude,
        String timezone,
        CurrentData current,
        DailyData daily
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CurrentData(
            String time,
            @JsonProperty("temperature_2m") double temperature,
            @JsonProperty("relative_humidity_2m") int humidity,
            @JsonProperty("apparent_temperature") double feelsLike,
            @JsonProperty("weather_code") int weatherCode,
            @JsonProperty("wind_speed_10m") double windSpeed,
            @JsonProperty("wind_direction_10m") int windDirection
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DailyData(
            List<String> time,
            @JsonProperty("weather_code") List<Integer> weatherCode,
            @JsonProperty("temperature_2m_max") List<Double> maxTemp,
            @JsonProperty("temperature_2m_min") List<Double> minTemp,
            @JsonProperty("precipitation_sum") List<Double> precipitation,
            @JsonProperty("wind_speed_10m_max") List<Double> maxWindSpeed
    ) {
    }
}