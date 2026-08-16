package com.aitp.orenda.trip;

import com.aitp.orenda.routing.RouteResponse;
import com.aitp.orenda.routing.RoutingService;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves travel time between two points, backed by the OSRM routing service
 * with a bounded in-memory cache. When routing is unavailable (network, service
 * down, mock in tests) it degrades to a straight-line estimate so the planner
 * never fails because of the routing provider.
 */
@Component
public class TravelTimeEstimator {

    private final RoutingService routingService;
    private final Map<String, Double> cache = new ConcurrentHashMap<>();

    public TravelTimeEstimator(RoutingService routingService) {
        this.routingService = routingService;
    }

    /**
     * Travel time in minutes between two points for the given transport mode.
     */
    public double minutesBetween(double fromLat, double fromLon, double toLat, double toLon,
                                 TripEnums.TransportMode mode) {
        String key = key(fromLat, fromLon, toLat, toLon, mode);
        Double cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        double minutes;
        try {
            RouteResponse route = routingService.getRoute(fromLat, fromLon, toLat, toLon, profile(mode));
            minutes = route.durationMinutes();
        } catch (Exception e) {
            minutes = straightLineMinutes(fromLat, fromLon, toLat, toLon, mode);
        }
        if (minutes <= 0) {
            minutes = straightLineMinutes(fromLat, fromLon, toLat, toLon, mode);
        }
        cache.put(key, minutes);
        return minutes;
    }

    /**
     * Straight-line (great-circle) distance in km between two points. Used to
     * enforce walking budgets and to fall back when routing fails.
     */
    public double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        return haversineKm(lat1, lon1, lat2, lon2);
    }

    private double straightLineMinutes(double lat1, double lon1, double lat2, double lon2,
                                       TripEnums.TransportMode mode) {
        double km = haversineKm(lat1, lon1, lat2, lon2);
        double speedKph = switch (mode) {
            case FOOT -> 4.5;
            case BIKE -> 15.0;
            case TRANSIT -> 20.0;
            case TAXI -> 30.0;
            case DRIVING -> 40.0;
        };
        return km / speedKph * 60.0;
    }

    public static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return 6371.0 * c;
    }

    private static String profile(TripEnums.TransportMode mode) {
        return switch (mode) {
            case FOOT -> "foot";
            case BIKE -> "bike";
            default -> "driving";
        };
    }

    private static String key(double aLat, double aLon, double bLat, double bLon, TripEnums.TransportMode mode) {
        return String.join("|", round(aLat), round(aLon), round(bLat), round(bLon), mode.name());
    }

    private static String round(double v) {
        return String.valueOf(Math.round(v * 10000.0) / 10000.0);
    }
}