package com.aitp.orenda.trip;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Pragmatic parser/evaluator for OSM {@code opening_hours} tags.
 * <p>
 * Supports the most common encodings: {@code 24/7}, {@code off}/{@code closed},
 * weekday lists and ranges (e.g. {@code Mo-Fr}, {@code Sa,Su}), one or more time
 * windows per day (comma separated), and multiple rules separated by {@code ;}.
 * Anything unrecognized yields {@link OpeningStatus#UNKNOWN}, which callers treat
 * as "don't exclude".
 */
public final class OpeningHoursEvaluator {

    public enum OpeningStatus {UNKNOWN, OPEN, CLOSED}

    private record Rule(int dayStart, int dayEnd, int startMin, int endMin) {
    }

    private record DaySpan(int start, int end) {
    }

    private OpeningHoursEvaluator() {
    }

    /**
     * @param openingHours the raw OSM tag (may be null/blank)
     * @param date         the trip date to evaluate
     * @param hourOfDay    local hour (0-23) of the intended visit
     */
    public static OpeningStatus evaluate(String openingHours, LocalDate date, int hourOfDay) {
        if (openingHours == null || openingHours.isBlank()) {
            return OpeningStatus.UNKNOWN;
        }
        String s = openingHours.toLowerCase().trim();
        if (s.contains("24/7") || s.contains("24 7")) {
            return OpeningStatus.OPEN;
        }
        if (s.equals("off") || s.equals("closed") || s.contains("open \"off\"")) {
            return OpeningStatus.CLOSED;
        }

        List<Rule> rules = parse(s);
        if (rules.isEmpty()) {
            return OpeningStatus.UNKNOWN;
        }

        int day = date.getDayOfWeek().getValue(); // 1=Mon .. 7=Sun
        int minute = hourOfDay * 60;

        for (Rule rule : rules) {
            if (rule.dayStart() <= day && day <= rule.dayEnd()
                    && rule.startMin() <= minute && minute < rule.endMin()) {
                return OpeningStatus.OPEN;
            }
        }
        return OpeningStatus.CLOSED;
    }

    private static List<Rule> parse(String s) {
        List<Rule> rules = new ArrayList<>();
        for (String part : s.split(";")) {
            String rule = part.trim();
            if (rule.isEmpty()) {
                continue;
            }
            int space = rule.indexOf(' ');
            if (space <= 0) {
                // A bare weekday span with no time window -> open all day
                DaySpan days = parseDays(rule);
                if (days != null) {
                    rules.add(new Rule(days.start(), days.end(), 0, 24 * 60));
                }
                continue;
            }
            DaySpan days = parseDays(rule.substring(0, space));
            if (days == null) {
                continue;
            }
            String timePart = rule.substring(space + 1).trim();
            for (String range : timePart.split(",")) {
                range = range.trim().replaceAll("[^0-9:\\-]", "");
                if (range.isEmpty() || range.equals("-")) {
                    continue;
                }
                int dash = range.indexOf('-');
                if (dash < 0) {
                    LocalTime start = parseTime(range);
                    if (start != null) {
                        rules.add(new Rule(days.start(), days.end(), start.getHour() * 60 + start.getMinute(), 24 * 60));
                    }
                    continue;
                }
                LocalTime start = parseTime(range.substring(0, dash));
                LocalTime end = parseTime(range.substring(dash + 1));
                if (start == null || end == null) {
                    continue;
                }
                int startMin = start.getHour() * 60 + start.getMinute();
                int endMin = end.getHour() * 60 + end.getMinute();
                if (endMin <= startMin) {
                    endMin = 24 * 60; // overnight ranges approximated to end of day
                }
                rules.add(new Rule(days.start(), days.end(), startMin, endMin));
            }
        }
        return rules;
    }

    private static DaySpan parseDays(String dayPart) {
        String d = dayPart.toLowerCase().trim();
        if (d.contains("ph") || d.contains("holiday")) {
            return null;
        }
        boolean any = false;
        int minStart = 7;
        int maxEnd = 1;
        for (String token : d.split(",")) {
            token = token.trim();
            if (token.contains("-")) {
                String[] bounds = token.split("-");
                Integer start = dayNum(bounds[0].trim());
                Integer end = dayNum(bounds[1].trim());
                if (start != null && end != null) {
                    any = true;
                    minStart = Math.min(minStart, start);
                    maxEnd = Math.max(maxEnd, end);
                }
            } else {
                Integer n = dayNum(token);
                if (n != null) {
                    any = true;
                    minStart = Math.min(minStart, n);
                    maxEnd = Math.max(maxEnd, n);
                }
            }
        }
        return any ? new DaySpan(minStart, maxEnd) : null;
    }

    private static Integer dayNum(String s) {
        return switch (s) {
            case "mo" -> 1;
            case "tu" -> 2;
            case "we" -> 3;
            case "th" -> 4;
            case "fr" -> 5;
            case "sa" -> 6;
            case "su" -> 7;
            default -> null;
        };
    }

    private static LocalTime parseTime(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            if (s.contains(":")) {
                String[] parts = s.split(":");
                return LocalTime.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
            }
            if (s.length() == 4) {
                return LocalTime.of(Integer.parseInt(s.substring(0, 2)), Integer.parseInt(s.substring(2, 4)));
            }
            if (s.length() == 3) {
                return LocalTime.of(Integer.parseInt(s.substring(0, 1)), Integer.parseInt(s.substring(1, 3)));
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }
}