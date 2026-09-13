package com.example.scheduler;

import com.example.scheduler.api.dto.CreateScheduleRequest;
import com.example.scheduler.api.dto.InstanceView;
import com.example.scheduler.api.dto.ScheduleView;
import com.example.scheduler.domain.InstanceStatus;
import com.example.scheduler.domain.ScheduleType;
import com.example.scheduler.domain.SkipReason;
import com.example.scheduler.error.BadRequestException;
import com.example.scheduler.error.ConflictException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PauseResumeIT extends BaseIT {

    private UUID calendarDef() {
        return scheduleService.create(new CreateScheduleRequest(
                "pausable", ScheduleType.CALENDAR, "0 0 9 * * *", "Europe/Berlin",
                null, null, null, null, 3)).id();
    }

    @Test
    void doublePauseAndUnpausedResumeAreConflicts() {
        UUID id = calendarDef();
        scheduleService.pause(id, null);
        assertThrows(ConflictException.class, () -> scheduleService.pause(id, null));
        scheduleService.resume(id);
        assertThrows(ConflictException.class, () -> scheduleService.resume(id));
    }

    @Test
    void pauseUntilMustBeInTheFuture() {
        UUID id = calendarDef();
        assertThrows(BadRequestException.class,
                () -> scheduleService.pause(id, clock.instant().minusSeconds(1)));
    }

    @Test
    void indefinitePauseSuppressesAllFutureOccurrencesUntilResume() {
        UUID id = calendarDef();
        Instant from = clock.instant();
        scheduleService.pause(id, null);
        assertTrue(scheduleService.get(id).paused());

        List<InstanceView> during = scheduleService.preview(id, from, from.plus(Duration.ofDays(3)));
        assertEquals(3, during.size());
        assertTrue(during.stream().allMatch(v -> v.status().equals("SKIPPED")
                && v.skipReason().equals(SkipReason.PAUSED.name())));

        clock.advance(Duration.ofDays(1));
        scheduleService.resume(id);
        assertFalse(scheduleService.get(id).paused());

        List<InstanceView> after = scheduleService.preview(id, from, from.plus(Duration.ofDays(3)));
        // day 1 remains paused (window [T0, T0+1d)), days 2 and 3 are planned again
        assertEquals(SkipReason.PAUSED.name(), after.get(0).skipReason());
        assertEquals(InstanceStatus.PLANNED.name(), after.get(1).status());
        assertEquals(InstanceStatus.PLANNED.name(), after.get(2).status());
    }

    @Test
    void pauseFlipsAlreadyMaterializedInstancesToSkipped() {
        UUID id = calendarDef();
        // instances for the next 7 days were materialized at creation
        long plannedBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM trigger_instance WHERE definition_id = ? AND status = 'PLANNED'",
                Long.class, id.toString());
        assertTrue(plannedBefore > 0);

        scheduleService.pause(id, clock.instant().plus(Duration.ofDays(2)));
        long skipped = jdbc.queryForObject(
                "SELECT COUNT(*) FROM trigger_instance WHERE definition_id = ? AND status = 'SKIPPED' AND skip_reason = 'PAUSED'",
                Long.class, id.toString());
        assertEquals(2, skipped, "occurrences in the next two days must become SKIPPED/PAUSED");
    }

    @Test
    void viewReflectsPauseState() {
        UUID id = calendarDef();
        ScheduleView before = scheduleService.get(id);
        assertFalse(before.paused());
        scheduleService.pause(id, null);
        assertTrue(scheduleService.get(id).paused());
        scheduleService.resume(id);
        assertFalse(scheduleService.get(id).paused());
    }
}
