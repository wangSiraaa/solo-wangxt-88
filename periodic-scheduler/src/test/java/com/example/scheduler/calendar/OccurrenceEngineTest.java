package com.example.scheduler.calendar;

import com.example.scheduler.domain.DstGapPolicy;
import com.example.scheduler.domain.DstOverlapPolicy;
import com.example.scheduler.domain.SkipReason;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OccurrenceEngineTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private final OccurrenceEngine engine = new OccurrenceEngine();

    /**
     * Whole-set verification over four years (1461 days, 8 DST transitions): the engine's
     * output is compared against an independently computed expectation (plain java.time
     * iteration over every date), not against a single next-run computation.
     */
    @Test
    void dailyCalendarMatchesIndependentBruteForceOverFourYears() {
        CronSpec spec = CronSpec.parse("0 0 9 * * *");
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2030-01-01T00:00:00Z");

        List<Instant> actual = engine.calendarOccurrences(spec, BERLIN, from, to,
                        DstGapPolicy.SHIFT_FORWARD, DstOverlapPolicy.FIRST)
                .stream().map(Occurrence::instant).toList();

        List<Instant> expected = new ArrayList<>();
        for (LocalDate d = LocalDate.of(2026, 1, 1); !d.isAfter(LocalDate.of(2029, 12, 31)); d = d.plusDays(1)) {
            Instant instant = LocalDateTime.of(d, LocalTime.of(9, 0)).atZone(BERLIN).toInstant();
            if (instant.isAfter(from) && !instant.isAfter(to)) {
                expected.add(instant);
            }
        }
        assertEquals(expected.size(), actual.size(), "occurrence count over 2026..2029");
        assertEquals(expected, actual);
    }

    @Test
    void leapDayCronFiresOnlyInLeapYears() {
        CronSpec spec = CronSpec.parse("0 0 9 29 2 *");
        List<Occurrence> occurrences = engine.calendarOccurrences(spec, BERLIN,
                Instant.parse("2027-01-01T00:00:00Z"), Instant.parse("2033-01-01T00:00:00Z"),
                DstGapPolicy.SHIFT_FORWARD, DstOverlapPolicy.FIRST);
        List<Instant> instants = occurrences.stream().map(Occurrence::instant).toList();
        assertEquals(List.of(
                Instant.parse("2028-02-29T08:00:00Z"),   // 09:00 CET
                Instant.parse("2032-02-29T08:00:00Z")), instants);
    }

    @Test
    void dstGapShiftForwardMovesIntoValidTime() {
        CronSpec spec = CronSpec.parse("0 30 2 * * *");
        List<Occurrence> occurrences = engine.calendarOccurrences(spec, BERLIN,
                Instant.parse("2026-03-28T00:00:00Z"), Instant.parse("2026-03-31T00:00:00Z"),
                DstGapPolicy.SHIFT_FORWARD, DstOverlapPolicy.FIRST);
        assertEquals(3, occurrences.size());
        Occurrence gapDay = occurrences.get(1);
        // 2026-03-29 02:30 does not exist -> shifted to 03:30 CEST = 01:30 UTC
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), gapDay.instant());
        assertEquals(LocalDateTime.of(2026, 3, 29, 3, 30), gapDay.localTime());
        assertEquals("+02:00", gapDay.offset().toString());
        assertNull(gapDay.skipReason());
    }

    @Test
    void dstGapSkipProducesSkippedOccurrenceWithReason() {
        CronSpec spec = CronSpec.parse("0 30 2 * * *");
        List<Occurrence> occurrences = engine.calendarOccurrences(spec, BERLIN,
                Instant.parse("2026-03-28T00:00:00Z"), Instant.parse("2026-03-31T00:00:00Z"),
                DstGapPolicy.SKIP, DstOverlapPolicy.FIRST);
        assertEquals(3, occurrences.size());
        Occurrence gapDay = occurrences.get(1);
        assertTrue(gapDay.skipped());
        assertEquals(SkipReason.DST_GAP, gapDay.skipReason());
        assertNull(gapDay.instant(), "a skipped gap occurrence has no UTC instant");
        assertEquals(LocalDateTime.of(2026, 3, 29, 2, 30), gapDay.localTime());
    }

    @Test
    void dstOverlapPolicies() {
        CronSpec spec = CronSpec.parse("0 30 2 * * *");
        Instant from = Instant.parse("2026-10-24T00:00:00Z");
        Instant to = Instant.parse("2026-10-27T00:00:00Z");

        List<Occurrence> first = engine.calendarOccurrences(spec, BERLIN, from, to,
                DstGapPolicy.SHIFT_FORWARD, DstOverlapPolicy.FIRST);
        assertEquals(List.of(
                Instant.parse("2026-10-24T00:30:00Z"),
                Instant.parse("2026-10-25T00:30:00Z"),   // first 02:30 (CEST)
                Instant.parse("2026-10-26T01:30:00Z")),
                first.stream().map(Occurrence::instant).toList());

        List<Occurrence> second = engine.calendarOccurrences(spec, BERLIN, from, to,
                DstGapPolicy.SHIFT_FORWARD, DstOverlapPolicy.SECOND);
        assertEquals(Instant.parse("2026-10-25T01:30:00Z"), second.get(1).instant()); // second 02:30 (CET)

        List<Occurrence> both = engine.calendarOccurrences(spec, BERLIN, from, to,
                DstGapPolicy.SHIFT_FORWARD, DstOverlapPolicy.BOTH);
        assertEquals(4, both.size(), "BOTH fires twice on the overlap day");
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), both.get(1).instant());
        assertEquals(Instant.parse("2026-10-25T01:30:00Z"), both.get(2).instant());
        assertEquals("+02:00", both.get(1).offset().toString());
        assertEquals("+01:00", both.get(2).offset().toString());
    }

    /**
     * The headline distinction: "every 24 hours" is NOT "9am local". Across the spring
     * forward the interval schedule stays glued to the UTC timeline while the calendar
     * schedule follows the wall clock.
     */
    @Test
    void fixedIntervalIsImmuneToDstButCalendarIsNot() {
        Instant anchor = Instant.parse("2026-03-28T08:00:00Z"); // == 09:00 CET that day
        Instant from = Instant.parse("2026-03-28T00:00:00Z");
        Instant to = Instant.parse("2026-03-31T00:00:00Z");

        List<Occurrence> interval = engine.intervalOccurrences(anchor, 86_400, from, to);
        assertEquals(List.of(
                Instant.parse("2026-03-28T08:00:00Z"),
                Instant.parse("2026-03-29T08:00:00Z"),   // local 10:00 after the jump
                Instant.parse("2026-03-30T08:00:00Z")),
                interval.stream().map(Occurrence::instant).toList());

        List<Occurrence> calendar = engine.calendarOccurrences(CronSpec.parse("0 0 9 * * *"),
                BERLIN, from, to, DstGapPolicy.SHIFT_FORWARD, DstOverlapPolicy.FIRST);
        assertEquals(List.of(
                Instant.parse("2026-03-28T08:00:00Z"),
                Instant.parse("2026-03-29T07:00:00Z"),   // still 09:00 local
                Instant.parse("2026-03-30T07:00:00Z")),
                calendar.stream().map(Occurrence::instant).toList());
    }

    @Test
    void intervalWindowBoundaries() {
        Instant anchor = Instant.parse("2026-01-01T00:00:00Z");
        // from is exclusive, to is inclusive
        List<Occurrence> occurrences = engine.intervalOccurrences(anchor, 60,
                Instant.parse("2026-01-01T00:01:00Z"), Instant.parse("2026-01-01T00:03:00Z"));
        assertEquals(List.of(
                Instant.parse("2026-01-01T00:02:00Z"),
                Instant.parse("2026-01-01T00:03:00Z")),
                occurrences.stream().map(Occurrence::instant).toList());
    }
}
