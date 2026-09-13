package com.example.scheduler.api.dto;

import com.example.scheduler.domain.DstGapPolicy;
import com.example.scheduler.domain.DstOverlapPolicy;
import com.example.scheduler.domain.ScheduleType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * Creation request. The two schedule kinds are strictly separated:
 *
 * <ul>
 *   <li>{@code FIXED_INTERVAL}: {@code intervalSeconds} (+ optional {@code anchorAt});
 *       {@code cron}/{@code timezone} must be absent.</li>
 *   <li>{@code CALENDAR}: {@code cron} + {@code timezone};
 *       {@code intervalSeconds}/{@code anchorAt} must be absent.</li>
 * </ul>
 * Mixing fields of the other kind is rejected with 400.
 */
public record CreateScheduleRequest(
        @NotBlank String name,
        @NotNull ScheduleType type,
        String cron,
        String timezone,
        Long intervalSeconds,
        Instant anchorAt,
        DstGapPolicy dstGapPolicy,
        DstOverlapPolicy dstOverlapPolicy,
        Integer maxRetries) {
}
