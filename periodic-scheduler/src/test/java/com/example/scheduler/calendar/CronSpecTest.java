package com.example.scheduler.calendar;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CronSpecTest {

    @Test
    void fiveFieldExpressionMeansSecondZero() {
        CronSpec spec = CronSpec.parse("30 2 * * *");
        assertEquals(1, spec.secondsOfDay().length);
        assertEquals(2 * 3600 + 30 * 60, spec.secondsOfDay()[0]);
    }

    @Test
    void namesRangesAndSteps() {
        CronSpec spec = CronSpec.parse("0 */15 9-17 * * MON-FRI");
        // 9:00 .. 17:45 every 15 minutes = 9 hours * 4 = 36 fire times
        assertEquals(36, spec.secondsOfDay().length);
        assertTrue(spec.matchesDay(LocalDate.parse("2026-09-14")));  // Monday
        assertFalse(spec.matchesDay(LocalDate.parse("2026-09-13"))); // Sunday
    }

    @Test
    void monthNames() {
        CronSpec spec = CronSpec.parse("0 0 9 * JAN *");
        assertTrue(spec.matchesDay(LocalDate.parse("2027-01-15")));
        assertFalse(spec.matchesDay(LocalDate.parse("2027-02-15")));
    }

    @Test
    void sundayIsZeroOrSeven() {
        CronSpec byZero = CronSpec.parse("0 0 9 * * 0");
        CronSpec bySeven = CronSpec.parse("0 0 9 * * 7");
        CronSpec byName = CronSpec.parse("0 0 9 * * SUN");
        LocalDate sunday = LocalDate.parse("2026-09-13");
        assertTrue(byZero.matchesDay(sunday));
        assertTrue(bySeven.matchesDay(sunday));
        assertTrue(byName.matchesDay(sunday));
    }

    @Test
    void restrictedDomAndDowAreOrEd() {
        CronSpec spec = CronSpec.parse("0 0 9 15 * MON");
        // 2026-03-15 is a Sunday: matches via day-of-month
        assertTrue(spec.matchesDay(LocalDate.parse("2026-03-15")));
        // 2026-03-16 is a Monday: matches via day-of-week
        assertTrue(spec.matchesDay(LocalDate.parse("2026-03-16")));
        // neither the 15th nor a Monday
        assertFalse(spec.matchesDay(LocalDate.parse("2026-03-17")));
    }

    @Test
    void lastDayOfMonth() {
        CronSpec spec = CronSpec.parse("0 0 9 L * *");
        assertTrue(spec.matchesDay(LocalDate.parse("2028-02-29")));  // leap February
        assertTrue(spec.matchesDay(LocalDate.parse("2026-02-28")));  // non-leap February
        assertFalse(spec.matchesDay(LocalDate.parse("2026-02-27")));
        assertTrue(spec.matchesDay(LocalDate.parse("2026-04-30")));
    }

    @Test
    void questionMarkMeansUnrestricted() {
        CronSpec spec = CronSpec.parse("0 0 9 ? * MON");
        assertTrue(spec.matchesDay(LocalDate.parse("2026-09-14")));  // Monday
        assertFalse(spec.matchesDay(LocalDate.parse("2026-09-15"))); // Tuesday
    }

    @Test
    void rejectsInvalidExpressions() {
        assertThrows(IllegalArgumentException.class, () -> CronSpec.parse(""));
        assertThrows(IllegalArgumentException.class, () -> CronSpec.parse("0 0 9 *"));
        assertThrows(IllegalArgumentException.class, () -> CronSpec.parse("0 0 25 * * *"));
        assertThrows(IllegalArgumentException.class, () -> CronSpec.parse("0 0 9 32 * *"));
        assertThrows(IllegalArgumentException.class, () -> CronSpec.parse("0 0 9 * FUNDAY"));
        assertThrows(IllegalArgumentException.class, () -> CronSpec.parse("0 0 9 * * 8"));
        assertThrows(IllegalArgumentException.class, () -> CronSpec.parse("0 0 9 * */0 *"));
        assertThrows(IllegalArgumentException.class, () -> CronSpec.parse("0 0 9 5-2 * *"));
    }

    @Test
    void rejectsDegenerateHighFrequencyExpressions() {
        // every second of every day = 86400 fire times/day -> belongs to FIXED_INTERVAL
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CronSpec.parse("* * * * * *"));
        assertTrue(e.getMessage().contains("FIXED_INTERVAL"), e.getMessage());
    }
}
