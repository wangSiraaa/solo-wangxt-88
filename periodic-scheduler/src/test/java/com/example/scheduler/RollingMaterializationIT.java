package com.example.scheduler;

import com.example.scheduler.support.InstanceSetDiff;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Rolling materialization with a small horizon and batch size: a 3-second-interval task is
 * accepted and its horizon is filled batch by batch, tracked by the materialization
 * watermark — no hard limit, no duplicates.
 */
@org.springframework.boot.test.context.SpringBootTest(properties = {
        "spring.quartz.auto-startup=false",
        "app.planner.horizon=PT2M",
        "app.planner.batch-size=25"})
class RollingMaterializationIT extends BaseIT {

    private static final Instant T0 = Instant.parse("2026-09-13T00:00:00Z");

    @Test
    void highFrequencyTaskIsMaterializedInRollingBatches() throws Exception {
        clock.setInstant(T0);
        String id = createSchedule("""
                {"name":"every-3s","type":"FIXED_INTERVAL","intervalSeconds":3,
                 "anchorAt":"2026-09-13T00:00:00Z"}
                """);
        UUID defId = UUID.fromString(id);

        // horizon 2min @ 3s = 41 occurrences (k=0..40, anchor included); batch size 25
        assertEquals(25, instanceRepository.countByDefinition(defId),
                "first rolling batch is inserted inline at create");
        assertEquals(T0.plusSeconds(72), materializedUntil(defId),
                "watermark sits at the last occurrence of batch 1 (k=24)");

        assertEquals(16, plannerService.materializeFor(defId),
                "second batch completes the horizon (k=25..40)");
        assertEquals(41, instanceRepository.countByDefinition(defId));
        assertEquals(T0.plusSeconds(120), materializedUntil(defId),
                "horizon covered: watermark == now + horizon");

        assertEquals(0, plannerService.materializeFor(defId),
                "horizon covered: further runs are no-ops until time advances");

        // roll the clock: the horizon slides forward and is refilled in batches
        clock.advance(Duration.ofSeconds(30));
        assertEquals(10, plannerService.materializeFor(defId),
                "30 more seconds of horizon = 10 more occurrences");
        assertEquals(51, instanceRepository.countByDefinition(defId));
        assertEquals(T0.plusSeconds(150), materializedUntil(defId));

        // no duplicates anywhere
        Long distinctKeys = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT occurrence_key) FROM trigger_instance WHERE definition_id = ?",
                Long.class, id);
        assertEquals(51L, distinctKeys, "occurrence keys must be unique");

        // full-set verification: exactly the expected UTC instants, independently computed
        List<InstanceSetDiff.Entry> expected = new ArrayList<>();
        for (int i = 0; i <= 50; i++) {
            Instant t = T0.plusSeconds(3L * i);
            expected.add(InstanceSetDiff.planned(
                    t.toString(), LocalDateTime.ofInstant(t, ZoneOffset.UTC).toString(), "Z"));
        }
        List<InstanceSetDiff.Entry> actual =
                persistedInstances(id, "2026-09-13T00:00:00Z", "2026-09-13T00:03:00Z");
        InstanceSetDiff.assertMatch("rolling 3s interval, 50 occurrences",
                "2026-09-13T00:00:00Z .. 2026-09-13T00:03:00Z", expected, actual);
    }

    private Instant materializedUntil(UUID defId) {
        Timestamp ts = jdbc.queryForObject(
                "SELECT materialized_until FROM schedule_definition WHERE id = ?",
                Timestamp.class, defId.toString());
        return ts == null ? null : ts.toInstant();
    }
}
