package com.example.scheduler;

import com.example.scheduler.support.InstanceSetDiff;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Calendar schedules roll day-by-day: a batch is always cut at a day boundary (every
 * occurrence of a started day is included), which keeps the continuation watermark safe.
 */
@org.springframework.boot.test.context.SpringBootTest(properties = {
        "spring.quartz.auto-startup=false",
        "app.planner.horizon=P3D",
        "app.planner.batch-size=4"})
class RollingCalendarMaterializationIT extends BaseIT {

    @Test
    void calendarBatchesAreCutAtDayBoundaries() throws Exception {
        clock.setInstant("2026-09-13T00:00:00Z");
        String id = createSchedule("""
                {"name":"nightly-hours","type":"CALENDAR","cron":"0 0 0-2 * * *","timezone":"UTC"}
                """);
        UUID defId = UUID.fromString(id);

        // 3 occurrences/day, batch 4: day 13 (3) fits, day 14 (3) completes the batch -> 6
        assertEquals(6, instanceRepository.countByDefinition(defId),
                "batch 1 = day 13 + day 14 (cut at day boundary, may exceed batch-size)");

        assertEquals(4, plannerService.materializeFor(defId),
                "batch 2 = day 15 + the single occurrence of day 16 inside the horizon");
        assertEquals(10, instanceRepository.countByDefinition(defId));
        assertEquals(0, plannerService.materializeFor(defId), "horizon covered");

        Long distinctKeys = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT occurrence_key) FROM trigger_instance WHERE definition_id = ?",
                Long.class, id);
        assertEquals(10L, distinctKeys, "no duplicate occurrences across batches");

        List<InstanceSetDiff.Entry> expected = List.of(
                InstanceSetDiff.planned("2026-09-13T00:00:00Z", "2026-09-13T00:00", "Z"),
                InstanceSetDiff.planned("2026-09-13T01:00:00Z", "2026-09-13T01:00", "Z"),
                InstanceSetDiff.planned("2026-09-13T02:00:00Z", "2026-09-13T02:00", "Z"),
                InstanceSetDiff.planned("2026-09-14T00:00:00Z", "2026-09-14T00:00", "Z"),
                InstanceSetDiff.planned("2026-09-14T01:00:00Z", "2026-09-14T01:00", "Z"),
                InstanceSetDiff.planned("2026-09-14T02:00:00Z", "2026-09-14T02:00", "Z"),
                InstanceSetDiff.planned("2026-09-15T00:00:00Z", "2026-09-15T00:00", "Z"),
                InstanceSetDiff.planned("2026-09-15T01:00:00Z", "2026-09-15T01:00", "Z"),
                InstanceSetDiff.planned("2026-09-15T02:00:00Z", "2026-09-15T02:00", "Z"),
                InstanceSetDiff.planned("2026-09-16T00:00:00Z", "2026-09-16T00:00", "Z"));
        InstanceSetDiff.assertMatch("rolling calendar, day-boundary batches",
                "2026-09-13T00:00:00Z .. 2026-09-16T00:00:00Z",
                expected, persistedInstances(id, "2026-09-13T00:00:00Z", "2026-09-16T00:00:00Z"));
    }
}
