package com.aitp.orenda.trip;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Exports a saved trip as iCal (.ics) or PDF.
 */
@Service
public class TripExportService {

    private static final String TIMEZONE = "Europe/Istanbul";
    private static final LocalTime DEFAULT_START = LocalTime.of(9, 30);
    private static final int DEFAULT_VISIT = 90;
    private static final int DEFAULT_TRAVEL = 15;

    public String toIcs(SavedTrip trip) {
        StringBuilder sb = new StringBuilder();
        sb.append("BEGIN:VCALENDAR\r\n");
        sb.append("VERSION:2.0\r\n");
        sb.append("PRODID:-//Orenda//Trip Export//EN\r\n");
        sb.append("CALSCALE:GREGORIAN\r\n");
        sb.append("METHOD:PUBLISH\r\n");
        sb.append("X-WR-CALNAME:").append(icsEscape(tripName(trip))).append("\r\n");

        for (SavedTripDay day : trip.getDays()) {
            if (day.getDate() == null || day.getStops() == null) {
                continue;
            }
            LocalTime cursor = DEFAULT_START;
            for (SavedTripStop stop : day.getStops()) {
                LocalTime start = stop.getStartTime() == null ? cursor : parse(stop.getStartTime(), cursor);
                int visit = stop.getVisitMinutes() == null ? DEFAULT_VISIT : stop.getVisitMinutes();
                LocalTime end = stop.getEndTime() == null ? start.plusMinutes(visit) : parse(stop.getEndTime(), start.plusMinutes(visit));
                appendEvent(sb, day.getDate(), start, end, stop);
                cursor = end.plusMinutes(stop.getTravelMinutes() == null ? DEFAULT_TRAVEL : stop.getTravelMinutes());
            }
        }

        sb.append("END:VCALENDAR\r\n");
        return sb.toString();
    }

    public byte[] toPdf(SavedTrip trip) {
        List<String> lines = new ArrayList<>();
        lines.add(tripName(trip));
        if (trip.getDestination() != null) {
            lines.add(trip.getDestination());
        }
        if (trip.getWeatherSummary() != null && !trip.getWeatherSummary().isBlank()) {
            lines.add("Weather: " + trip.getWeatherSummary());
        }
        lines.add("");
        if (trip.getSummary() != null) {
            lines.add("Summary: " + trip.getSummary());
        }
        lines.add("");

        for (SavedTripDay day : trip.getDays()) {
            String header = "Day " + day.getDay()
                    + (day.getDate() == null ? "" : " — " + day.getDate());
            if (day.getWeather() != null && !day.getWeather().isBlank()) {
                header += "  (" + day.getWeather() + ")";
            }
            lines.add("════════════════════════════════════════");
            lines.add(header);
            lines.add("════════════════════════════════════════");
            if (day.getStops() == null || day.getStops().isEmpty()) {
                lines.add("  (free day — nothing scheduled)");
                lines.add("");
                continue;
            }
            LocalTime cursor = DEFAULT_START;
            for (SavedTripStop stop : day.getStops()) {
                LocalTime start = stop.getStartTime() == null ? cursor : parse(stop.getStartTime(), cursor);
                int visit = stop.getVisitMinutes() == null ? DEFAULT_VISIT : stop.getVisitMinutes();
                LocalTime end = stop.getEndTime() == null ? start.plusMinutes(visit) : parse(stop.getEndTime(), start.plusMinutes(visit));
                String time = start + " - " + end;
                String name = stop.getNameTr() == null ? "Unnamed stop" : stop.getNameTr();
                StringBuilder category = new StringBuilder();
                if (stop.getCategory() != null) category.append(stop.getCategory());
                if (stop.getSubcategory() != null) {
                    if (!category.isEmpty()) category.append(" / ");
                    category.append(stop.getSubcategory());
                }
                lines.add("  " + time + "   " + name);
                if (!category.isEmpty()) {
                    lines.add("        " + category);
                }
                if (stop.getReasons() != null && !stop.getReasons().isEmpty()) {
                    lines.add("        - " + String.join("; ", stop.getReasons()));
                }
                cursor = end.plusMinutes(stop.getTravelMinutes() == null ? DEFAULT_TRAVEL : stop.getTravelMinutes());
            }
            if (day.getNotes() != null) {
                for (String note : day.getNotes()) {
                    lines.add("  Note: " + note);
                }
            }
            lines.add("");
        }

        if (trip.getNarrative() != null && !trip.getNarrative().isBlank()) {
            lines.add("Narrative");
            lines.add(trip.getNarrative());
        }
        return SimplePdfWriter.write(tripName(trip), lines);
    }

    private void appendEvent(StringBuilder sb, LocalDate date, LocalTime start, LocalTime end,
                             SavedTripStop stop) {
        sb.append("BEGIN:VEVENT\r\n");
        sb.append("UID:orenda-stop-").append(stop.getId() == null ? stop.hashCode() : stop.getId())
                .append("@orenda.app\r\n");
        sb.append("DTSTAMP:").append(ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")))
                .append("\r\n");
        sb.append("DTSTART;TZID=").append(TIMEZONE).append(":")
                .append(date.format(DateTimeFormatter.BASIC_ISO_DATE)).append("T")
                .append(start.format(DateTimeFormatter.ofPattern("HHmmss"))).append("\r\n");
        sb.append("DTEND;TZID=").append(TIMEZONE).append(":")
                .append(date.format(DateTimeFormatter.BASIC_ISO_DATE)).append("T")
                .append(end.format(DateTimeFormatter.ofPattern("HHmmss"))).append("\r\n");
        sb.append("SUMMARY:").append(icsEscape(stop.getNameTr() == null ? "Unnamed stop" : stop.getNameTr()))
                .append("\r\n");
        StringBuilder description = new StringBuilder();
        if (stop.getCategory() != null) description.append(stop.getCategory());
        if (stop.getSubcategory() != null) {
            if (!description.isEmpty()) description.append(" / ");
            description.append(stop.getSubcategory());
        }
        if (stop.getReasons() != null && !stop.getReasons().isEmpty()) {
            if (!description.isEmpty()) description.append(". ");
            description.append(String.join("; ", stop.getReasons()));
        }
        if (!description.isEmpty()) {
            sb.append("DESCRIPTION:").append(icsEscape(description.toString())).append("\r\n");
        }
        sb.append("END:VEVENT\r\n");
    }

    private String tripName(SavedTrip trip) {
        return trip.getName() == null || trip.getName().isBlank()
                ? "My trip" + (trip.getDestination() == null ? "" : " to " + trip.getDestination())
                : trip.getName();
    }

    private LocalTime parse(String time, LocalTime fallback) {
        try {
            return LocalTime.parse(time);
        } catch (Exception e) {
            return fallback;
        }
    }

    private String icsEscape(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
                .replace("\r\n", "\\n").replace("\n", "\\n");
    }

    public String filename(SavedTrip trip, String format) {
        String base = (trip.getName() == null ? "trip" : trip.getName())
                .toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (base.isBlank()) base = "trip";
        return base + "-" + trip.getId() + "." + ("pdf".equalsIgnoreCase(format) ? "pdf" : "ics");
    }

    public byte[] encode(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }
}