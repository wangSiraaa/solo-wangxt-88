package com.example.scheduler;

import com.example.scheduler.support.InstanceSetDiff.Entry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.example.scheduler.support.InstanceSetDiff.assertMatch;
import static com.example.scheduler.support.InstanceSetDiff.paused;
import static com.example.scheduler.support.InstanceSetDiff.planned;
import static com.example.scheduler.support.InstanceSetDiff.skippedDstGap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Verifies WHOLE future instance sets (never a single next-run computation) for the tricky
 * scenarios: leap days, DST gaps, DST overlaps, interval-vs-calendar divergence, pause/resume.
 * Every scenario prints a full expected-vs-actual report (see stdout) and fails listing the
 * differences in both directions.
 */
class FutureInstanceSetVerificationIT extends BaseIT {

    @Test
    void leapDayScheduleFiresOnlyInLeapYears() throws Exception {
        String id = createSchedule("""
                {"name":"leap-day","type":"CALENDAR","cron":"0 0 9 29 2 *","timezone":"Europe/Berlin"}
                """);

        List<Entry> expected = List.of(
                planned("2028-02-29T08:00:00Z", "2028-02-29T09:00", "+01:00"),
                planned("2032-02-29T08:00:00Z", "2032-02-29T09:00", "+01:00"));
        List<Entry> actual = preview(id, "2027-01-01T00:00:00Z", "2033-01-01T00:00:00Z");
        assertMatch("leap day 09:00 Europe/Berlin, 2027..2032", "2027-01-01Z .. 2033-01-01Z", expected, actual);

        // The materialized (persisted) set around 2028-02-29 must match the preview exactly,
        // and re-materialization must be idempotent. (Planner horizon is 7 days.)
        clock.setInstant("2028-02-25T00:00:00Z");
        plannerService.materializeFor(java.util.UUID.fromString(id));
        plannerService.materializeFor(java.util.UUID.fromString(id));
        List<Entry> persisted = persistedInstances(id, "2028-02-25T00:00:00Z", "2028-03-15T00:00:00Z");
        assertMatch("leap day materialized set (Feb 2028)", "2028-02-25Z .. 2028-03-15Z",
                List.of(planned("2028-02-29T08:00:00Z", "2028-02-29T09:00", "+01:00")), persisted);
    }

    @Test
    void dstGapShiftForward() throws Exception {
        String id = createSchedule("""
                {"name":"gap-shift","type":"CALENDAR","cron":"0 30 2 * * *","timezone":"Europe/Berlin",
                 "dstGapPolicy":"SHIFT_FORWARD"}
                """);
        List<Entry> expected = List.of(
                planned("2026-03-27T01:30:00Z", "2026-03-27T02:30", "+01:00"),
                planned("2026-03-28T01:30:00Z", "2026-03-28T02:30", "+01:00"),
                // 2026-03-29 02:30 does not exist -> shifted to 03:30 CEST
                planned("2026-03-29T01:30:00Z", "2026-03-29T03:30", "+02:00"),
                planned("2026-03-30T00:30:00Z", "2026-03-30T02:30", "+02:00"));
        List<Entry> actual = preview(id, "2026-03-27T00:00:00Z", "2026-03-31T00:00:00Z");
        assertMatch("DST gap 2026-03-29, SHIFT_FORWARD", "2026-03-27Z .. 2026-03-31Z", expected, actual);
    }

    @Test
    void dstGapSkipReportsReason() throws Exception {
        String id = createSchedule("""
                {"name":"gap-skip","type":"CALENDAR","cron":"0 30 2 * * *","timezone":"Europe/Berlin",
                 "dstGapPolicy":"SKIP"}
                """);
        List<Entry> expected = List.of(
                planned("2026-03-27T01:30:00Z", "2026-03-27T02:30", "+01:00"),
                planned("2026-03-28T01:30:00Z", "2026-03-28T02:30", "+01:00"),
                // no such instant: SKIPPED with reason DST_GAP, UTC time null
                skippedDstGap("2026-03-29T02:30"),
                planned("2026-03-30T00:30:00Z", "2026-03-30T02:30", "+02:00"));
        List<Entry> actual = preview(id, "2026-03-27T00:00:00Z", "2026-03-31T00:00:00Z");
        assertMatch("DST gap 2026-03-29, SKIP", "2026-03-27Z .. 2026-03-31Z", expected, actual);
    }

    @Test
    void dstOverlapFirstSecondBoth() throws Exception {
        String first = createSchedule("""
                {"name":"overlap-first","type":"CALENDAR","cron":"0 30 2 * * *","timezone":"Europe/Berlin",
                 "dstOverlapPolicy":"FIRST"}
                """);
        String second = createSchedule("""
                {"name":"overlap-second","type":"CALENDAR","cron":"0 30 2 * * *","timezone":"Europe/Berlin",
                 "dstOverlapPolicy":"SECOND"}
                """);
        String both = createSchedule("""
                {"name":"overlap-both","type":"CALENDAR","cron":"0 30 2 * * *","timezone":"Europe/Berlin",
                 "dstOverlapPolicy":"BOTH"}
                """);
        String from = "2026-10-24T00:00:00Z";
        String to = "2026-10-27T00:00:00Z";

        assertMatch("DST overlap 2026-10-25, FIRST", from + " .. " + to,
                List.of(
                        planned("2026-10-24T00:30:00Z", "2026-10-24T02:30", "+02:00"),
                        planned("2026-10-25T00:30:00Z", "2026-10-25T02:30", "+02:00"),
                        planned("2026-10-26T01:30:00Z", "2026-10-26T02:30", "+01:00")),
                preview(first, from, to));

        assertMatch("DST overlap 2026-10-25, SECOND", from + " .. " + to,
                List.of(
                        planned("2026-10-24T00:30:00Z", "2026-10-24T02:30", "+02:00"),
                        planned("2026-10-25T01:30:00Z", "2026-10-25T02:30", "+01:00"),
                        planned("2026-10-26T01:30:00Z", "2026-10-26T02:30", "+01:00")),
                preview(second, from, to));

        assertMatch("DST overlap 2026-10-25, BOTH", from + " .. " + to,
                List.of(
                        planned("2026-10-24T00:30:00Z", "2026-10-24T02:30", "+02:00"),
                        planned("2026-10-25T00:30:00Z", "2026-10-25T02:30", "+02:00"),
                        planned("2026-10-25T01:30:00Z", "2026-10-25T02:30", "+01:00"),
                        planned("2026-10-26T01:30:00Z", "2026-10-26T02:30", "+01:00")),
                preview(both, from, to));
    }

    /**
     * The user's headline point: "every day at 9am local" and "every 24 hours" are different
     * rules. Anchored on the same instant, they diverge the moment DST changes the offset.
     */
    @Test
    void fixedIntervalAndCalendarDivergeAcrossDst() throws Exception {
        String interval = createSchedule("""
                {"name":"every-24h","type":"FIXED_INTERVAL","intervalSeconds":86400,
                 "anchorAt":"2026-03-28T08:00:00Z"}
                """);
        String calendar = createSchedule("""
                {"name":"every-day-9am","type":"CALENDAR","cron":"0 0 9 * * *","timezone":"Europe/Berlin"}
                """);
        String from = "2026-03-28T00:00:00Z";
        String to = "2026-03-31T00:00:00Z";

        List<Entry> intervalActual = preview(interval, from, to);
        assertMatch("every 24h from 2026-03-28T08:00Z (UTC timeline)", from + " .. " + to,
                List.of(
                        planned("2026-03-28T08:00:00Z", "2026-03-28T08:00", "Z"),
                        planned("2026-03-29T08:00:00Z", "2026-03-29T08:00", "Z"),
                        planned("2026-03-30T08:00:00Z", "2026-03-30T08:00", "Z")),
                intervalActual);

        List<Entry> calendarActual = preview(calendar, from, to);
        assertMatch("every day 09:00 Europe/Berlin (wall clock)", from + " .. " + to,
                List.of(
                        planned("2026-03-28T08:00:00Z", "2026-03-28T09:00", "+01:00"),
                        planned("2026-03-29T07:00:00Z", "2026-03-29T09:00", "+02:00"),
                        planned("2026-03-30T07:00:00Z", "2026-03-30T09:00", "+02:00")),
                calendarActual);

        Set<String> intervalUtc = intervalActual.stream().map(Entry::scheduledAtUtc).collect(Collectors.toSet());
        Set<String> calendarUtc = calendarActual.stream().map(Entry::scheduledAtUtc).collect(Collectors.toSet());
        assertNotEquals(intervalUtc, calendarUtc,
                "the two rules must produce different UTC trigger times across the DST transition");
        System.out.println("UTC divergence across 2026-03-29 DST jump: interval=" + intervalUtc
                + " calendar=" + calendarUtc);
    }

    @Test
    void pauseAndResumeShapeTheInstanceSet() throws Exception {
        clock.setInstant("2026-10-23T12:00:00Z");
        String id = createSchedule("""
                {"name":"pausable","type":"CALENDAR","cron":"0 0 9 * * *","timezone":"Europe/Berlin"}
                """);
        // pause [2026-10-23T12:00Z, 2026-10-27T00:00Z): occurrences of the 24th/25th/26th fall inside
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/schedules/{id}/pause", id)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"until\":\"2026-10-27T00:00:00Z\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

        String from = "2026-10-23T00:00:00Z";
        String to = "2026-10-29T00:00:00Z";
        List<Entry> expected = List.of(
                planned("2026-10-23T07:00:00Z", "2026-10-23T09:00", "+02:00"),
                paused("2026-10-24T07:00:00Z", "2026-10-24T09:00", "+02:00"),
                // the clock falls back at 03:00 on 10-25, so 09:00 on 10-25 is already CET
                paused("2026-10-25T08:00:00Z", "2026-10-25T09:00", "+01:00"),
                paused("2026-10-26T08:00:00Z", "2026-10-26T09:00", "+01:00"),
                planned("2026-10-27T08:00:00Z", "2026-10-27T09:00", "+01:00"),
                planned("2026-10-28T08:00:00Z", "2026-10-28T09:00", "+01:00"));
        assertMatch("paused window 10-24 .. 10-26 (preview)", from + " .. " + to,
                expected, preview(id, from, to));

        // resume two days later; occurrences inside the pause window stay SKIPPED (no backfill)
        clock.setInstant("2026-10-26T12:00:00Z");
        Long countBeforeResume = jdbc.queryForObject(
                "SELECT COUNT(*) FROM trigger_instance WHERE definition_id = ?", Long.class, id);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/schedules/{id}/resume", id))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

        assertMatch("after resume, pause window is not backfilled (preview)", from + " .. " + to,
                expected, preview(id, from, to));

        // The persisted set: occurrences of 24/25/26 were materialized as SKIPPED/PAUSED when the
        // pause was declared; 27/28 are PLANNED. The 23rd is in the past at materialization time.
        List<Entry> expectedPersisted = List.of(
                paused("2026-10-24T07:00:00Z", "2026-10-24T09:00", "+02:00"),
                paused("2026-10-25T08:00:00Z", "2026-10-25T09:00", "+01:00"),
                paused("2026-10-26T08:00:00Z", "2026-10-26T09:00", "+01:00"),
                planned("2026-10-27T08:00:00Z", "2026-10-27T09:00", "+01:00"),
                planned("2026-10-28T08:00:00Z", "2026-10-28T09:00", "+01:00"));
        assertMatch("persisted instance set after resume", from + " .. " + to,
                expectedPersisted, persistedInstances(id, from, to));

        // Resume must not have created extra instances: the row count is unchanged by resume.
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM trigger_instance WHERE definition_id = ?", Long.class, id);
        assertEquals(countBeforeResume, count, "resume must not backfill or duplicate instances");
    }

    @Test
    void leapDayEveryFourYearsOverLongHorizon() throws Exception {
        // A second leap-day check with a different rule shape: yearly on the last day of February.
        String id = createSchedule("""
                {"name":"feb-last-day","type":"CALENDAR","cron":"0 0 12 L 2 *","timezone":"UTC"}
                """);
        List<Entry> expected = List.of(
                planned("2028-02-29T12:00:00Z", "2028-02-29T12:00", "Z"),
                planned("2029-02-28T12:00:00Z", "2029-02-28T12:00", "Z"),
                planned("2030-02-28T12:00:00Z", "2030-02-28T12:00", "Z"),
                planned("2031-02-28T12:00:00Z", "2031-02-28T12:00", "Z"),
                planned("2032-02-29T12:00:00Z", "2032-02-29T12:00", "Z"));
        List<Entry> actual = preview(id, "2028-01-01T00:00:00Z", "2033-01-01T00:00:00Z");
        assertMatch("last day of February 12:00 UTC, 2028..2032", "2028-01-01Z .. 2033-01-01Z",
                expected, actual);
    }

    @Test
    void materializationIsIdempotentUnderRepeatedPlannerRuns() throws Exception {
        clock.setInstant("2026-09-13T00:00:00Z");
        String id = createSchedule("""
                {"name":"hourly","type":"FIXED_INTERVAL","intervalSeconds":3600,
                 "anchorAt":"2026-09-13T00:00:00Z"}
                """);
        java.util.UUID defId = java.util.UUID.fromString(id);
        long afterCreate = instanceRepository.countByDefinition(defId);
        int insertedAgain = plannerService.materializeFor(defId);
        plannerService.materializeFor(defId);
        assertEquals(0, insertedAgain, "second planner run must insert nothing");
        assertEquals(afterCreate, instanceRepository.countByDefinition(defId),
                "repeated planner runs must not change the instance count");
        // and the clock moving forward does not duplicate already-materialized occurrences either
        clock.advance(Duration.ofHours(3));
        plannerService.materializeFor(defId);
        assertEquals(afterCreate + 3, instanceRepository.countByDefinition(defId),
                "only the newly entered horizon occurrences may be added");
    }
}
