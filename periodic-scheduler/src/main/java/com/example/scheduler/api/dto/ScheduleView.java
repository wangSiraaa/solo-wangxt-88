package com.example.scheduler.api.dto;

import java.time.Instant;
import java.util.UUID;

public record ScheduleView(UUID id,
                           String name,
                           String type,
                           String cron,
                           String timezone,
                           Long intervalSeconds,
                           Instant anchorAt,
                           String dstGapPolicy,
                           String dstOverlapPolicy,
                           int maxRetries,
                           boolean paused,
                           Instant createdAt) {
}
