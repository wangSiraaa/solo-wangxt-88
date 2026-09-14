package com.example.scheduler.persistence;

import com.example.scheduler.domain.DstGapPolicy;
import com.example.scheduler.domain.DstOverlapPolicy;
import com.example.scheduler.domain.ScheduleType;

import java.time.Instant;
import java.util.UUID;

public record ScheduleDefinitionRow(UUID id,
                                    String name,
                                    ScheduleType type,
                                    String cronExpression,
                                    String timezone,
                                    Long intervalSeconds,
                                    Instant anchorAt,
                                    DstGapPolicy dstGapPolicy,
                                    DstOverlapPolicy dstOverlapPolicy,
                                    int maxRetries,
                                    Instant createdAt,
                                    Instant updatedAt,
                                    Instant materializedUntil) {
}
