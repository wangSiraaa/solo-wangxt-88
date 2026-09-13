package com.example.scheduler.persistence;

import com.example.scheduler.domain.InstanceStatus;
import com.example.scheduler.domain.SkipReason;

import java.time.Instant;
import java.util.UUID;

public record TriggerInstanceRow(UUID id,
                                 UUID definitionId,
                                 String occurrenceKey,
                                 Instant scheduledAtUtc,
                                 Instant sortAt,
                                 String originalZone,
                                 String localTime,
                                 String utcOffset,
                                 InstanceStatus status,
                                 SkipReason skipReason,
                                 int retryCount,
                                 Instant nextRetryAt,
                                 Instant createdAt,
                                 Instant updatedAt) {
}
