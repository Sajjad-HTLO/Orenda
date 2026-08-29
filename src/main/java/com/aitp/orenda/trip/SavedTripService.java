package com.aitp.orenda.trip;

import com.aitp.orenda.model.PoiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Persists and manages saved trips for an authenticated user.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SavedTripService {

    private final SavedTripRepository repository;
    private final TripRecalculationService recalculationService;

    @Transactional
    public SavedTripDetailResponse save(UUID userId, SaveTripRequest request) {
        TripPlanResponse plan = request.getPlan();
        if (plan == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "plan must not be null");
        }

        LocalDate[] range = deriveDates(plan);
        String destination = request.getDestination();
        String name = request.getName() == null || request.getName().isBlank()
                ? (destination == null ? "Untitled trip" : "Trip to " + destination)
                : request.getName().trim();

        SavedTrip trip = SavedTrip.builder()
                .userId(userId)
                .name(name)
                .destination(destination)
                .startDate(range[0])
                .endDate(range[1])
                .tripDays(Math.max(1, plan.getTripDays()))
                .summary(plan.getSummary())
                .weatherSummary(plan.getWeatherSummary())
                .narrative(plan.getNarrative())
                .preferenceInsight(plan.getPreferenceInsight())
                .notes(plan.getNotes() == null ? List.of() : plan.getNotes())
                .days(mapDays(plan.getDayPlan()))
                .build();

        SavedTrip inserted = repository.insertTrip(trip);
        repository.replacePlan(inserted.getId(), inserted.getDays());
        log.info("Saved trip {} for user {}", inserted.getId(), userId);
        return get(userId, inserted.getId());
    }

    public List<SavedTripSummaryResponse> list(UUID userId) {
        return repository.listForUser(userId).stream()
                .map(SavedTripSummaryResponse::from)
                .toList();
    }

    public SavedTripDetailResponse get(UUID userId, UUID tripId) {
        SavedTrip trip = requireOwned(userId, tripId);
        return SavedTripDetailResponse.from(trip);
    }

    /**
     * Returns the full saved trip (with days and stops) for export purposes.
     */
    public SavedTrip loadForExport(UUID userId, UUID tripId) {
        return requireOwned(userId, tripId);
    }

    @Transactional
    public SavedTripDetailResponse update(UUID userId, UUID tripId, UpdateTripRequest request) {
        SavedTrip existing = requireOwned(userId, tripId);

        LocalDate startDate = request.getStartDate() != null ? request.getStartDate() : existing.getStartDate();
        LocalDate endDate = request.getEndDate() != null ? request.getEndDate() : existing.getEndDate();
        int tripDays = request.getDays() != null && !request.getDays().isEmpty()
                ? request.getDays().size()
                : existing.getTripDays();

        repository.updateDetails(tripId,
                request.getName() == null ? existing.getName() : request.getName().trim(),
                request.getDestination() == null ? existing.getDestination() : request.getDestination().trim(),
                startDate,
                endDate,
                tripDays,
                existing.getSummary(),
                existing.getWeatherSummary(),
                existing.getNarrative(),
                existing.getPreferenceInsight(),
                request.getNotes() == null ? existing.getNotes() : request.getNotes());

        if (request.getDays() != null) {
            repository.replacePlan(tripId, mapUpdateDays(request.getDays()));
        }
        return get(userId, tripId);
    }

    @Transactional
    public void delete(UUID userId, UUID tripId, boolean archive) {
        if (archive) {
            repository.archiveTrip(tripId, userId);
        } else {
            repository.deleteTrip(tripId, userId);
        }
    }

    @Transactional
    public SavedTripDetailResponse recalculate(UUID userId, RecalculateTripRequest request) {
        SavedTrip trip = requireOwned(userId, request.getTripId());
        SavedTrip adjusted = recalculationService.apply(trip, request);
        repository.updateDetails(adjusted.getId(), adjusted.getName(), adjusted.getDestination(),
                adjusted.getStartDate(), adjusted.getEndDate(), adjusted.getTripDays(),
                adjusted.getSummary(), adjusted.getWeatherSummary(), adjusted.getNarrative(),
                adjusted.getPreferenceInsight(), adjusted.getNotes());
        repository.replacePlan(adjusted.getId(), adjusted.getDays());
        return get(userId, adjusted.getId());
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private SavedTrip requireOwned(UUID userId, UUID tripId) {
        return repository.findByIdForUser(tripId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Trip not found"));
    }

    private LocalDate[] deriveDates(TripPlanResponse plan) {
        LocalDate start = null;
        LocalDate end = null;
        if (plan.getDayPlan() != null) {
            for (TripPlanResponse.DayPlan day : plan.getDayPlan()) {
                LocalDate date = parseDate(day.getDate());
                if (date == null) {
                    continue;
                }
                if (start == null || date.isBefore(start)) {
                    start = date;
                }
                if (end == null || date.isAfter(end)) {
                    end = date;
                }
            }
        }
        if (start == null) {
            start = LocalDate.now();
        }
        if (end == null) {
            end = start.plusDays(Math.max(0, plan.getTripDays() - 1));
        }
        return new LocalDate[]{start, end};
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    private List<SavedTripDay> mapDays(List<TripPlanResponse.DayPlan> dayPlans) {
        if (dayPlans == null) {
            return List.of();
        }
        List<SavedTripDay> days = new ArrayList<>();
        for (TripPlanResponse.DayPlan dp : dayPlans) {
            List<SavedTripStop> stops = new ArrayList<>();
            if (dp.getItems() != null) {
                int order = 0;
                for (TripPlanResponse.ScoredPoi item : dp.getItems()) {
                    stops.add(mapStop(item, order++));
                }
            }
            days.add(SavedTripDay.builder()
                    .day(dp.getDay())
                    .date(parseDate(dp.getDate()))
                    .weather(dp.getWeather())
                    .narrative(dp.getNarrative())
                    .notes(dp.getNotes() == null ? List.of() : dp.getNotes())
                    .stops(stops)
                    .build());
        }
        return days;
    }

    private List<SavedTripDay> mapUpdateDays(List<UpdateTripRequest.Day> updateDays) {
        List<SavedTripDay> days = new ArrayList<>();
        for (UpdateTripRequest.Day d : updateDays) {
            List<SavedTripStop> stops = new ArrayList<>();
            if (d.getStops() != null) {
                int order = 0;
                for (UpdateTripRequest.Stop s : d.getStops()) {
                    stops.add(SavedTripStop.builder()
                            .poiId(s.getPoiId())
                            .nameTr(s.getNameTr())
                            .nameEn(s.getNameEn())
                            .category(s.getCategory())
                            .subcategory(s.getSubcategory())
                            .lat(s.getLat())
                            .lon(s.getLon())
                            .score(s.getScore())
                            .travelMinutes(s.getTravelMinutes())
                            .visitMinutes(s.getVisitMinutes())
                            .startTime(s.getStartTime())
                            .endTime(s.getEndTime())
                            .openAtScheduledTime(s.getOpenAtScheduledTime())
                            .reasons(s.getReasons())
                            .factors(s.getFactors())
                            .sortOrder(order++)
                            .build());
                }
            }
            days.add(SavedTripDay.builder()
                    .day(d.getDay())
                    .date(d.getDate())
                    .weather(d.getWeather())
                    .narrative(d.getNarrative())
                    .notes(d.getNotes() == null ? List.of() : d.getNotes())
                    .stops(stops)
                    .build());
        }
        return days;
    }

    private SavedTripStop mapStop(TripPlanResponse.ScoredPoi item, int order) {
        PoiResponse poi = item.getPoi();
        return SavedTripStop.builder()
                .poiId(poi == null ? null : poi.getId())
                .nameTr(poi == null ? null : poi.getNameTr())
                .nameEn(poi == null ? null : poi.getNameEn())
                .category(poi == null ? null : poi.getCategory())
                .subcategory(poi == null ? null : poi.getSubcategory())
                .lat(poi == null ? null : poi.getLat())
                .lon(poi == null ? null : poi.getLon())
                .score(item.getScore())
                .travelMinutes(item.getTravelMinutes())
                .visitMinutes(item.getVisitMinutes())
                .startTime(item.getStartTime())
                .endTime(item.getEndTime())
                .openAtScheduledTime(item.getOpenAtScheduledTime())
                .reasons(item.getReasons() == null ? List.of() : item.getReasons())
                .factors(item.getFactors() == null ? java.util.Map.of() : item.getFactors())
                .sortOrder(order)
                .build();
    }
}