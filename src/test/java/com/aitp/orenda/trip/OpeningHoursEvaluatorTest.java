package com.aitp.orenda.trip;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class OpeningHoursEvaluatorTest {

    private final LocalDate monday = LocalDate.of(2026, 8, 17);
    private final LocalDate saturday = LocalDate.of(2026, 8, 15);

    @Test
    void parses_simple_weekday_range() {
        assertThat(OpeningHoursEvaluator.evaluate("Mo-Fr 09:00-18:00", monday, 14))
                .isEqualTo(OpeningHoursEvaluator.OpeningStatus.OPEN);
        assertThat(OpeningHoursEvaluator.evaluate("Mo-Fr 09:00-18:00", monday, 20))
                .isEqualTo(OpeningHoursEvaluator.OpeningStatus.CLOSED);
        assertThat(OpeningHoursEvaluator.evaluate("Mo-Fr 09:00-18:00", saturday, 14))
                .isEqualTo(OpeningHoursEvaluator.OpeningStatus.CLOSED);
    }

    @Test
    void parses_24_7_and_off() {
        assertThat(OpeningHoursEvaluator.evaluate("24/7", saturday, 3))
                .isEqualTo(OpeningHoursEvaluator.OpeningStatus.OPEN);
        assertThat(OpeningHoursEvaluator.evaluate("off", monday, 12))
                .isEqualTo(OpeningHoursEvaluator.OpeningStatus.CLOSED);
    }

    @Test
    void parses_multiple_rules_and_windows() {
        String rule = "Mo-Fr 09:00-12:00,13:00-17:00; Sa 10:00-16:00";
        assertThat(OpeningHoursEvaluator.evaluate(rule, monday, 10))
                .isEqualTo(OpeningHoursEvaluator.OpeningStatus.OPEN);
        assertThat(OpeningHoursEvaluator.evaluate(rule, monday, 12))
                .isEqualTo(OpeningHoursEvaluator.OpeningStatus.CLOSED); // lunch break
        assertThat(OpeningHoursEvaluator.evaluate(rule, monday, 14))
                .isEqualTo(OpeningHoursEvaluator.OpeningStatus.OPEN);
        assertThat(OpeningHoursEvaluator.evaluate(rule, saturday, 12))
                .isEqualTo(OpeningHoursEvaluator.OpeningStatus.OPEN);
    }

    @Test
    void unknown_when_unparseable_or_blank() {
        assertThat(OpeningHoursEvaluator.evaluate(null, monday, 12))
                .isEqualTo(OpeningHoursEvaluator.OpeningStatus.UNKNOWN);
        assertThat(OpeningHoursEvaluator.evaluate("", monday, 12))
                .isEqualTo(OpeningHoursEvaluator.OpeningStatus.UNKNOWN);
        assertThat(OpeningHoursEvaluator.evaluate("by appointment", monday, 12))
                .isEqualTo(OpeningHoursEvaluator.OpeningStatus.UNKNOWN);
    }
}