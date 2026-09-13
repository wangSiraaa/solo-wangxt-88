package com.example.scheduler.service;

import com.example.scheduler.api.dto.CreateScheduleRequest;
import com.example.scheduler.api.dto.InstanceView;
import com.example.scheduler.api.dto.ScheduleView;
import com.example.scheduler.calendar.CronSpec;
import com.example.scheduler.domain.DstGapPolicy;
import com.example.scheduler.domain.DstOverlapPolicy;
import com.example.scheduler.domain.ScheduleType;
import com.example.scheduler.error.BadRequestException;
import com.example.scheduler.error.ConflictException;
import com.example.scheduler.error.NotFoundException;
import com.example.scheduler.persistence.InstanceRepository;
import com.example.scheduler.persistence.PauseWindowRepository;
import com.example.scheduler.persistence.PauseWindowRow;
import com.example.scheduler.persistence.ScheduleDefinitionRow;
import com.example.scheduler.persistence.ScheduleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
public class ScheduleService {

    /** Largest window accepted by preview / instance listing. */
    static final Duration MAX_WINDOW = Duration.ofDays(3660);

    private final ScheduleRepository schedules;
    private final InstanceRepository instances;
    private final PauseWindowRepository pauseWindows;
    private final PlannerService planner;
    private final Clock clock;

    public ScheduleService(ScheduleRepository schedules,
                           InstanceRepository instances,
                           PauseWindowRepository pauseWindows,
                           PlannerService planner,
                           Clock clock) {
        this.schedules = schedules;
        this.instances = instances;
        this.pauseWindows = pauseWindows;
        this.planner = planner;
        this.clock = clock;
    }

    @Transactional
    public ScheduleView create(CreateScheduleRequest req) {
        validate(req);
        Instant now = clock.instant();
        ScheduleDefinitionRow row = new ScheduleDefinitionRow(
                UUID.randomUUID(),
                req.name().trim(),
                req.type(),
                req.type() == ScheduleType.CALENDAR ? req.cron().trim() : null,
                req.type() == ScheduleType.CALENDAR ? req.timezone().trim() : null,
                req.type() == ScheduleType.FIXED_INTERVAL ? req.intervalSeconds() : null,
                req.type() == ScheduleType.FIXED_INTERVAL
                        ? (req.anchorAt() != null ? req.anchorAt().truncatedTo(ChronoUnit.SECONDS) : now)
                        : null,
                req.dstGapPolicy() != null ? req.dstGapPolicy() : DstGapPolicy.SHIFT_FORWARD,
                req.dstOverlapPolicy() != null ? req.dstOverlapPolicy() : DstOverlapPolicy.FIRST,
                req.maxRetries() != null ? req.maxRetries() : 3,
                now, now);
        schedules.insert(row);
        try {
            planner.materializeFor(row.id());
        } catch (IllegalStateException e) {
            // e.g. the schedule would materialize more occurrences than the horizon limit
            throw new BadRequestException(e.getMessage());
        }
        return toView(row, false);
    }

    public ScheduleView get(UUID id) {
        return toView(findOrThrow(id), isPaused(id));
    }

    public List<ScheduleView> list() {
        return schedules.findAll().stream().map(row -> toView(row, isPaused(row.id()))).toList();
    }

    @Transactional
    public void delete(UUID id) {
        if (schedules.delete(id) == 0) {
            throw new NotFoundException("schedule " + id + " not found");
        }
    }

    @Transactional
    public ScheduleView pause(UUID id, Instant until) {
        findOrThrow(id);
        Instant now = clock.instant();
        if (pauseWindows.findActive(id, now).isPresent()) {
            throw new ConflictException("schedule " + id + " is already paused");
        }
        if (until != null && !until.isAfter(now)) {
            throw new BadRequestException("pause 'until' must be in the future");
        }
        pauseWindows.insert(new PauseWindowRow(UUID.randomUUID(), id, now, until));
        planner.reconcilePaused(id);
        return toView(findOrThrow(id), true);
    }

    @Transactional
    public ScheduleView resume(UUID id) {
        findOrThrow(id);
        int closed = pauseWindows.closeActive(id, clock.instant());
        if (closed == 0) {
            throw new ConflictException("schedule " + id + " is not paused");
        }
        // Occurrences inside the pause window stay SKIPPED/PAUSED (no backfill);
        // future occurrences materialize normally from the next planner tick on.
        return toView(findOrThrow(id), false);
    }

    /** Computed future instance set (not persisted): UTC times, original zone, skip reasons. */
    public List<InstanceView> preview(UUID id, Instant from, Instant to) {
        ScheduleDefinitionRow def = findOrThrow(id);
        checkWindow(from, to);
        return planner.previewFor(def, from, to).stream()
                .map(occ -> InstanceView.ofOccurrence(id, occ))
                .toList();
    }

    /** Persisted instance set for a window. */
    public List<InstanceView> instances(UUID id, Instant from, Instant to) {
        findOrThrow(id);
        checkWindow(from, to);
        return instances.findWindowWithLease(id, from, to).stream().map(InstanceView::of).toList();
    }

    private ScheduleDefinitionRow findOrThrow(UUID id) {
        return schedules.findById(id)
                .orElseThrow(() -> new NotFoundException("schedule " + id + " not found"));
    }

    private boolean isPaused(UUID id) {
        return pauseWindows.findActive(id, clock.instant()).isPresent();
    }

    private static void checkWindow(Instant from, Instant to) {
        if (from == null || to == null) {
            throw new BadRequestException("'from' and 'to' are required (ISO-8601 instants)");
        }
        if (!to.isAfter(from)) {
            throw new BadRequestException("'to' must be after 'from'");
        }
        if (Duration.between(from, to).compareTo(MAX_WINDOW) > 0) {
            throw new BadRequestException("window too large (max " + MAX_WINDOW.toDays() + " days)");
        }
    }

    private static void validate(CreateScheduleRequest req) {
        switch (req.type()) {
            case CALENDAR -> {
                if (req.cron() == null || req.cron().isBlank()) {
                    throw new BadRequestException("cron is required for CALENDAR schedules");
                }
                try {
                    CronSpec.parse(req.cron());
                } catch (IllegalArgumentException e) {
                    throw new BadRequestException("invalid cron: " + e.getMessage());
                }
                if (req.timezone() == null || req.timezone().isBlank()) {
                    throw new BadRequestException("timezone is required for CALENDAR schedules");
                }
                try {
                    ZoneId.of(req.timezone().trim());
                } catch (RuntimeException e) {
                    throw new BadRequestException("unknown timezone: '" + req.timezone() + "'");
                }
                if (req.intervalSeconds() != null) {
                    throw new BadRequestException(
                            "intervalSeconds belongs to FIXED_INTERVAL schedules; a CALENDAR schedule fires by wall clock in its timezone");
                }
                if (req.anchorAt() != null) {
                    throw new BadRequestException("anchorAt belongs to FIXED_INTERVAL schedules");
                }
            }
            case FIXED_INTERVAL -> {
                if (req.intervalSeconds() == null || req.intervalSeconds() < 1) {
                    throw new BadRequestException("intervalSeconds >= 1 is required for FIXED_INTERVAL schedules");
                }
                if (req.cron() != null) {
                    throw new BadRequestException(
                            "cron belongs to CALENDAR schedules; a FIXED_INTERVAL schedule is timezone-independent");
                }
                if (req.timezone() != null) {
                    throw new BadRequestException(
                            "timezone belongs to CALENDAR schedules; a FIXED_INTERVAL schedule is anchored in UTC");
                }
            }
        }
        if (req.maxRetries() != null && req.maxRetries() < 0) {
            throw new BadRequestException("maxRetries must be >= 0");
        }
    }

    private ScheduleView toView(ScheduleDefinitionRow row, boolean paused) {
        return new ScheduleView(row.id(), row.name(), row.type().name(), row.cronExpression(),
                row.timezone(), row.intervalSeconds(), row.anchorAt(),
                row.dstGapPolicy().name(), row.dstOverlapPolicy().name(), row.maxRetries(),
                paused, row.createdAt());
    }
}
