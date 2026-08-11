package com.aitp.orenda.weather;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/weather")
@RequiredArgsConstructor
public class WeatherController {

    private final WeatherService weatherService;

    /**
     * Current conditions + daily forecast for a coordinate.
     * GET /api/weather?lat=41.01&lon=28.97&days=7
     * <p>
     * Powered by Open-Meteo (https://open-meteo.com) — free, no API key required.
     */
    @GetMapping
    public WeatherResponse getWeather(
            @RequestParam double lat,
            @RequestParam double lon,
            @RequestParam(defaultValue = "7") int days) {

        int safeDays = Math.max(1, Math.min(days, 16));
        return weatherService.getWeather(lat, lon, safeDays);
    }
}
