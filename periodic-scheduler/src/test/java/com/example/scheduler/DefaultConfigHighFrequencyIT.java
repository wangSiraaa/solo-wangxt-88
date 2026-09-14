package com.example.scheduler;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reported inconsistency, fixed: a 3-second-interval task must be creatable under the
 * DEFAULT configuration (horizon P7D, batch-size 10000) — no APP_PLANNER_HORIZON override,
 * no frequency rejection. The 7-day horizon (201600 occurrences) is filled in rolling
 * batches of 10000.
 */
class DefaultConfigHighFrequencyIT extends BaseIT {

    private static final Instant T0 = Instant.parse("2026-09-13T00:00:00Z");

    @Test
    void threeSecondIntervalIsAcceptedUnderDefaultConfiguration() throws Exception {
        clock.setInstant(T0);
        String id = createSchedule("""
                {"name":"every-3s","type":"FIXED_INTERVAL","intervalSeconds":3,
                 "anchorAt":"2026-09-13T00:00:00Z"}
                """);
        UUID defId = UUID.fromString(id);

        // first rolling batch inline at create: 10000 occurrences (k=0..9999) = 29997s of horizon
        assertEquals(10_000, instanceRepository.countByDefinition(defId));
        assertEquals(Instant.parse("2026-09-13T08:19:57Z"), materializedUntil(defId),
                "watermark after batch 1: T0 + 9999*3s");
        assertHorizonBound(defId);

        // second batch: another 10000 (k=10000..19999), watermark advances monotonically
        assertEquals(10_000, plannerService.materializeFor(defId));
        assertEquals(20_000, instanceRepository.countByDefinition(defId));
        assertEquals(Instant.parse("2026-09-13T16:39:57Z"), materializedUntil(defId));
        assertHorizonBound(defId);

        // no duplicates
        Long distinctKeys = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT occurrence_key) FROM trigger_instance WHERE definition_id = ?",
                Long.class, id);
        assertEquals(20_000L, distinctKeys, "occurrence keys must be unique across batches");
    }

    private void assertHorizonBound(UUID defId) {
        Instant maxSortAt = jdbc.queryForObject(
                "SELECT MAX(sort_at) FROM trigger_instance WHERE definition_id = ?",
                Timestamp.class, defId.toString()).toInstant();
        assertTrue(!maxSortAt.isAfter(clock.instant().plus(Duration.ofDays(7))),
                "materialized instances must not exceed now + horizon");
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM trigger_instance WHERE definition_id = ? AND sort_at > ?",
                Long.class, defId.toString(), Timestamp.from(clock.instant().plus(Duration.ofDays(7))));
        assertEquals(0L, count, "no instance may be materialized beyond the horizon");
    }

    private Instant materializedUntil(UUID defId) {
        return jdbc.queryForObject(
                "SELECT materialized_until FROM schedule_definition WHERE id = ?",
                Timestamp.class, defId.toString()).toInstant();
    }
}
