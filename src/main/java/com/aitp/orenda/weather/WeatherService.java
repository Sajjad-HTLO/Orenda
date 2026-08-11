package com.aitp.orenda.weather;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class WeatherService {

    private static final String BASE_URL = "https://api.open-meteo.com";

    // WMO Weather Interpretation Codes (WW)
    private static final Map<Integer, String> WMO_DESCRIPTIONS = Map.ofEntries(
            Map.entry(0, "Clear sky"),
            Map.entry(1, "Mainly clear"),
            Map.entry(2, "Partly cloudy"),
            Map.entry(3, "Overcast"),
            Map.entry(45, "Fog"),
            Map.entry(48, "Freezing fog"),
            Map.entry(51, "Light drizzle"),
            Map.entry(53, "Moderate drizzle"),
            Map.entry(55, "Dense drizzle"),
            Map.entry(61, "Slight rain"),
            Map.entry(63, "Moderate rain"),
            Map.entry(65, "Heavy rain"),
            Map.entry(71, "Slight snow"),
            Map.entry(73, "Moderate snow"),
            Map.entry(75, "Heavy snow"),
            Map.entry(77, "Snow grains"),
            Map.entry(80, "Rain showers"),
            Map.entry(81, "Moderate showers"),
            Map.entry(82, "Violent showers"),
            Map.entry(85, "Slight snow showers"),
            Map.entry(86, "Heavy snow showers"),
            Map.entry(95, "Thunderstorm"),
            Map.entry(96, "Thunderstorm with hail"),
            Map.entry(99, "Thunderstorm with heavy hail")
    );

    private final RestClient restClient = RestClient.builder().baseUrl(BASE_URL).build();

    public WeatherResponse getWeather(double lat, double lon, int days) {
        OpenMeteoResponse raw = restClient.get()
                .uri(u -> u
                        .path("/v1/forecast")
                        .queryParam("latitude", lat)
                        .queryParam("longitude", lon)
                        .queryParam("current",
                                "temperature_2m,relative_humidity_2m,apparent_temperature," +
                                        "weather_code,wind_speed_10m,wind_direction_10m")
                        .queryParam("daily",
                                "weather_code,temperature_2m_max,temperature_2m_min," +
                                        "precipitation_sum,wind_speed_10m_max")
                        .queryParam("timezone", "auto")
                        .queryParam("forecast_days", days)
                        .build())
                .retrieve()
                .body(OpenMeteoResponse.class);

        return map(raw);
    }

    private WeatherResponse map(OpenMeteoResponse r) {
        var loc = new WeatherResponse.Location(r.latitude(), r.longitude(), r.timezone());

        var c = r.current();
        var current = new WeatherResponse.CurrentConditions(
                c.temperature(), c.feelsLike(), c.humidity(),
                c.windSpeed(), c.windDirection(),
                c.weatherCode(), describe(c.weatherCode())
        );

        var d = r.daily();
        List<WeatherResponse.DailyForecast> daily = new ArrayList<>();
        for (int i = 0; i < d.time().size(); i++) {
            daily.add(new WeatherResponse.DailyForecast(
                    d.time().get(i),
                    d.maxTemp().get(i),
                    d.minTemp().get(i),
                    d.precipitation().get(i),
                    d.maxWindSpeed().get(i),
                    d.weatherCode().get(i),
                    describe(d.weatherCode().get(i))
            ));
        }

        return new WeatherResponse(loc, current, daily);
    }

    private String describe(int code) {
        return WMO_DESCRIPTIONS.getOrDefault(code, "Unknown (code " + code + ")");
    }
}
