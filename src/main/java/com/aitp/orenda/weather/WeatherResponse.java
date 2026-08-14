package com.aitp.orenda.weather;

import java.util.List;

public record WeatherResponse(
        Location location,
        CurrentConditions current,
        List<DailyForecast> daily
) {

    public record Location(double latitude, double longitude, String timezone) {
    }

    public record CurrentConditions(
            double temperature,
            double feelsLike,
            int humidity,
            double windSpeed,
            int windDirection,
            int weatherCode,
            String description
    ) {
    }

    public record DailyForecast(
            String date,
            double maxTemp,
            double minTemp,
            double precipitation,
            double maxWindSpeed,
            int weatherCode,
            String description
    ) {
    }
}