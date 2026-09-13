package com.example.scheduler.api.dto;

import com.example.scheduler.calendar.Occurrence;
import com.example.scheduler.domain.InstanceStatus;
import com.example.scheduler.persistence.InstanceWithLease;
import com.example.scheduler.persistence.TriggerInstanceRow;

import java.time.Instant;
import java.util.UUID;

/**
 * One trigger instance (persisted or previewed). Always carries the original timezone, the
 * wall-clock time in that zone, the UTC trigger time, and — when not planned — the skip reason.
 */
public record InstanceView(UUID id,
                           UUID definitionId,
                           String occurrenceKey,
                           String status,
                           Instant scheduledAtUtc,
                           String originalZone,
                           String localTime,
                           String offset,
                           String skipReason,
                           int retryCount,
                           Instant nextRetryAt,
                           LeaseView lease) {

    public static InstanceView of(InstanceWithLease iwl) {
        TriggerInstanceRow i = iwl.instance();
        return new InstanceView(i.id(), i.definitionId(), i.occurrenceKey(), i.status().name(),
                i.scheduledAtUtc(), i.originalZone(), i.localTime(), i.utcOffset(),
                i.skipReason() != null ? i.skipReason().name() : null,
                i.retryCount(), i.nextRetryAt(),
                iwl.lease() != null ? LeaseView.of(iwl.lease()) : null);
    }

    /** Preview entry (not persisted): id and lease are null. */
    public static InstanceView ofOccurrence(UUID definitionId, Occurrence occ) {
        return new InstanceView(null, definitionId, occ.occurrenceKey(),
                occ.skipped() ? InstanceStatus.SKIPPED.name() : InstanceStatus.PLANNED.name(),
                occ.instant(), occ.zoneId(), occ.localTime().toString(),
                occ.offset() != null ? occ.offset().toString() : null,
                occ.skipReason() != null ? occ.skipReason().name() : null,
                0, null, null);
    }
}
