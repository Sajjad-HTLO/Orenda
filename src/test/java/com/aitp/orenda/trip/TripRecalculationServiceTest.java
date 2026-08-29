package com.aitp.orenda.trip;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TripRecalculationServiceTest {

    private final TripRecalculationService service = new TripRecalculationService();

    private SavedTripStop stop(UUID id, String name, String category, String subcategory,
                               String start, String end, Integer visit) {
        return SavedTripStop.builder()
                .id(id)
                .nameTr(name)
                .category(category)
                .subcategory(subcategory)
                .startTime(start)
                .endTime(end)
                .travelMinutes(10)
                .visitMinutes(visit)
                .sortOrder(0)
                .build();
    }

    private SavedTrip tripWithDays(SavedTripDay... days) {
        return SavedTrip.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .name("Test trip")
                .startDate(LocalDate.of(2026, 8, 15))
                .endDate(LocalDate.of(2026, 8, 15))
                .tripDays(1)
                .notes(List.of("original"))
                .days(List.of(days))
                .build();
    }

    @Test
    void rain_keeps_indoor_stops_first() {
        UUID outdoorId = UUID.randomUUID();
        UUID indoorId = UUID.randomUUID();
        SavedTripDay day = SavedTripDay.builder()
                .day(1)
                .date(LocalDate.of(2026, 8, 15))
                .stops(List.of(
                        stop(outdoorId, "Rooftop Terrace", "leisure", "viewpoint", "09:30", "10:30", 60),
                        stop(indoorId, "Archaeology Museum", "culture", "museum", "11:00", "13:00", 120)))
                .build();

        SavedTrip result = service.apply(tripWithDays(day),
                RecalculateTripRequest.builder()
                        .tripId(UUID.randomUUID())
                        .event(RecalculateTripRequest.Event.RAIN)
                        .build());

        List<SavedTripStop> stops = result.getDays().get(0).getStops();
        assertThat(stops.get(0).getId()).isEqualTo(indoorId);
        assertThat(stops.get(1).getId()).isEqualTo(outdoorId);
        assertThat(stops.get(0).getStartTime()).isEqualTo("09:30");
        assertThat(result.getNotes().get(0)).contains("recalculated for rain");
        assertThat(result.getDays().get(0).getNotes())
                .anyMatch(note -> note.contains("indoor stops moved earlier"));
    }

    @Test
    void delay_shifts_remaining_times_forward() {
        SavedTripDay day = SavedTripDay.builder()
                .day(1)
                .date(LocalDate.of(2026, 8, 15))
                .stops(List.of(
                        stop(UUID.randomUUID(), "A", "culture", "museum", "10:00", "11:00", 60),
                        stop(UUID.randomUUID(), "B", "leisure", "park", "11:30", "12:30", 60)))
                .build();

        SavedTrip result = service.apply(tripWithDays(day),
                RecalculateTripRequest.builder()
                        .tripId(UUID.randomUUID())
                        .event(RecalculateTripRequest.Event.DELAY)
                        .delayMinutes(45)
                        .build());

        List<SavedTripStop> stops = result.getDays().get(0).getStops();
        assertThat(stops.get(0).getStartTime()).isEqualTo("10:45");
        assertThat(stops.get(0).getEndTime()).isEqualTo("11:45");
        assertThat(stops.get(1).getStartTime()).isEqualTo("12:15");
    }

    @Test
    void delay_without_minutes_is_rejected() {
        SavedTripDay day = SavedTripDay.builder().day(1).stops(List.of(
                stop(UUID.randomUUID(), "A", "culture", "museum", "10:00", "11:00", 60))).build();

        assertThatThrownBy(() -> service.apply(tripWithDays(day),
                RecalculateTripRequest.builder()
                        .tripId(UUID.randomUUID())
                        .event(RecalculateTripRequest.Event.DELAY)
                        .build()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void road_closure_drops_the_unreachable_stop_and_retimes() {
        UUID droppedId = UUID.randomUUID();
        UUID keptId = UUID.randomUUID();
        SavedTripDay day = SavedTripDay.builder()
                .day(1)
                .date(LocalDate.of(2026, 8, 15))
                .stops(List.of(
                        stop(droppedId, "Blue Mosque", "historic", "mosque", "09:30", "10:30", 60),
                        stop(keptId, "Hagia Sophia", "culture", "museum", "11:00", "13:00", 120)))
                .build();

        SavedTrip result = service.apply(tripWithDays(day),
                RecalculateTripRequest.builder()
                        .tripId(UUID.randomUUID())
                        .event(RecalculateTripRequest.Event.ROAD_CLOSURE)
                        .affectedStopId(droppedId)
                        .build());

        List<SavedTripStop> stops = result.getDays().get(0).getStops();
        assertThat(stops).hasSize(1);
        assertThat(stops.get(0).getId()).isEqualTo(keptId);
        assertThat(stops.get(0).getStartTime()).isEqualTo("11:00");
        assertThat(result.getDays().get(0).getNotes())
                .anyMatch(note -> note.contains("Blue Mosque is unreachable"));
    }

    @Test
    void road_closure_for_unknown_stop_is_not_found() {
        SavedTripDay day = SavedTripDay.builder().day(1).stops(List.of(
                stop(UUID.randomUUID(), "A", "culture", "museum", "10:00", "11:00", 60))).build();

        assertThatThrownBy(() -> service.apply(tripWithDays(day),
                RecalculateTripRequest.builder()
                        .tripId(UUID.randomUUID())
                        .event(RecalculateTripRequest.Event.ROAD_CLOSURE)
                        .affectedStopId(UUID.randomUUID())
                        .build()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}