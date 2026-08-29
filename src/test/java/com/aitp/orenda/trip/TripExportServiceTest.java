package com.aitp.orenda.trip;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TripExportServiceTest {

    private final TripExportService service = new TripExportService();

    private SavedTrip sampleTrip() {
        SavedTripStop stop = SavedTripStop.builder()
                .id(UUID.randomUUID())
                .poiId(UUID.randomUUID().toString())
                .nameTr("Topkapı Palace")
                .category("historic")
                .subcategory("palace")
                .startTime("09:40")
                .endTime("11:40")
                .travelMinutes(12)
                .visitMinutes(120)
                .reasons(List.of("Matches your interest in history"))
                .build();
        SavedTripDay day = SavedTripDay.builder()
                .id(UUID.randomUUID())
                .day(1)
                .date(LocalDate.of(2026, 8, 15))
                .weather("Slight rain, 20°C")
                .stops(List.of(stop))
                .build();
        return SavedTrip.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .name("İstanbul trip")
                .destination("Istanbul")
                .startDate(LocalDate.of(2026, 8, 15))
                .endDate(LocalDate.of(2026, 8, 15))
                .tripDays(1)
                .summary("Found 1 suggestions for your 1-day trip to Istanbul.")
                .days(List.of(day))
                .build();
    }

    @Test
    void ics_is_a_valid_calendar_with_events() {
        String ics = service.toIcs(sampleTrip());
        assertThat(ics).startsWith("BEGIN:VCALENDAR");
        assertThat(ics).contains("END:VCALENDAR");
        assertThat(ics).contains("BEGIN:VEVENT");
        assertThat(ics).contains("DTSTART;TZID=Europe/Istanbul:20260815T094000");
        assertThat(ics).contains("DTEND;TZID=Europe/Istanbul:20260815T114000");
        assertThat(ics).contains("SUMMARY:Topkapı Palace");
    }

    @Test
    void ics_escapes_special_characters() {
        SavedTripStop stop = SavedTripStop.builder()
                .id(UUID.randomUUID())
                .nameTr("Lunch; Café, city center")
                .startTime("12:00")
                .endTime("13:00")
                .build();
        SavedTripDay day = SavedTripDay.builder().day(1).date(LocalDate.of(2026, 8, 15)).stops(List.of(stop)).build();
        SavedTrip trip = SavedTrip.builder().id(UUID.randomUUID()).userId(UUID.randomUUID()).name("T").tripDays(1)
                .days(List.of(day)).build();

        String ics = service.toIcs(trip);
        assertThat(ics).contains("SUMMARY:Lunch\\; Café\\, city center");
    }

    @Test
    void pdf_output_is_a_valid_pdf() {
        byte[] pdf = service.toPdf(sampleTrip());
        String head = new String(pdf, StandardCharsets.ISO_8859_1);
        assertThat(pdf).startsWith("%PDF-1.4".getBytes(StandardCharsets.ISO_8859_1));
        assertThat(head).contains("%%EOF");
        assertThat(head).contains("Day 1");
        assertThat(head).contains("Topkapi Palace");
    }
}