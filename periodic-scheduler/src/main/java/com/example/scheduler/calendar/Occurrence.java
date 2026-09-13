package com.example.scheduler.calendar;

import com.example.scheduler.domain.SkipReason;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * One computed occurrence of a schedule.
 *
 * @param instant       UTC trigger time; {@code null} when the local time does not exist
 *                      (DST gap under SKIP policy) — there is no such instant
 * @param localTime     wall-clock time in the original zone (after shifting, for gap/SHIFT_FORWARD)
 * @param offset        UTC offset actually used; {@code null} for gap-skipped occurrences
 * @param zoneId        original zone of the schedule ("UTC" for fixed intervals)
 * @param skipReason    {@code null} when the occurrence is planned
 * @param occurrenceKey stable identity of this occurrence within its definition; used for
 *                      idempotent materialization across any number of planner processes
 * @param sortInstant   instant used for window queries/ordering (the gap transition instant
 *                      for gap-skipped occurrences)
 */
public record Occurrence(Instant instant,
                         LocalDateTime localTime,
                         ZoneOffset offset,
                         String zoneId,
                         SkipReason skipReason,
                         String occurrenceKey,
                         Instant sortInstant) {

    public boolean skipped() {
        return skipReason != null;
    }

    /** Copy flagged as paused (keeps its instant — the instant exists, it is just suppressed). */
    public Occurrence paused() {
        return new Occurrence(instant, localTime, offset, zoneId, SkipReason.PAUSED, occurrenceKey, sortInstant);
    }
}
