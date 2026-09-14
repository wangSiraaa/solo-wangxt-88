package com.example.scheduler.service;

import com.example.scheduler.calendar.CronSpec;
import com.example.scheduler.calendar.Occurrence;
import com.example.scheduler.calendar.OccurrenceEngine;
import com.example.scheduler.domain.InstanceStatus;
import com.example.scheduler.domain.ScheduleType;
import com.example.scheduler.persistence.InstanceRepository;
import com.example.scheduler.persistence.PauseWindowRepository;
import com.example.scheduler.persistence.PauseWindowRow;
import com.example.scheduler.persistence.ScheduleDefinitionRow;
import com.example.scheduler.persistence.ScheduleRepository;
import com.example.scheduler.persistence.TriggerInstanceRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Turns schedule definitions into trigger instances. Materialization is idempotent across any
 * number of concurrent planner processes: the occurrence key is stable and
 * {@code (definition_id, occurrence_key)} is unique, so racing planners insert nothing twice.
 */
@Service
public class PlannerService {

    private static final Logger log = LoggerFactory.getLogger(PlannerService.class);

    private final ScheduleRepository schedules;
    private final InstanceRepository instances;
    private final PauseWindowRepository pauseWindows;
    private final OccurrenceEngine engine;
    private final Clock clock;
    private final Duration horizon;
    private final int maxOccurrencesPerRun;

    public PlannerService(ScheduleRepository schedules,
                          InstanceRepository instances,
                          PauseWindowRepository pauseWindows,
                          OccurrenceEngine engine,
                          Clock clock,
                          @Value("${app.planner.horizon:P7D}") Duration horizon,
                          @Value("${app.planner.max-occurrences-per-run:100000}") int maxOccurrencesPerRun) {
        this.schedules = schedules;
        this.instances = instances;
        this.pauseWindows = pauseWindows;
        this.engine = engine;
        this.clock = clock;
        this.horizon = horizon;
        this.maxOccurrencesPerRun = maxOccurrencesPerRun;
    }

    /** Materialize occurrences of one definition over [now, now + horizon]. */
    @Transactional
    public int materializeFor(UUID definitionId) {
        return schedules.findById(definitionId).map(def -> {
            Instant now = clock.instant();
            List<Occurrence> occurrences = occurrencesFor(def, now.minusSeconds(1), now.plus(horizon));
            if (occurrences.size() > maxOccurrencesPerRun) {
                throw new IllegalStateException("definition " + definitionId + " would materialize "
                        + occurrences.size() + " occurrences within horizon " + horizon
                        + " (limit " + maxOccurrencesPerRun + "); shorten the horizon or the frequency");
            }
            int inserted = 0;
            for (Occurrence occ : occurrences) {
                if (instances.insertIgnore(toRow(def, occ, now))) {
                    inserted++;
                }
            }
            return inserted;
        }).orElse(0);
    }

    /** Materialize all definitions and reconcile pause windows. Per-definition failures are isolated. */
    public int materializeAll() {
        int total = 0;
        for (ScheduleDefinitionRow def : schedules.findAll()) {
            try {
                total += materializeFor(def.id());
                reconcilePaused(def.id());
            } catch (Exception e) {
                log.error("materialization failed for definition {}: {}", def.id(), e.toString());
            }
        }
        return total;
    }

    /** Flip still-planned instances that fall into a pause window to SKIPPED/PAUSED. */
    @Transactional
    public int reconcilePaused(UUID definitionId) {
        return instances.reconcilePaused(definitionId, clock.instant());
    }

    /** Pure computation for the preview endpoint: occurrences with the pause overlay applied. */
    public List<Occurrence> previewFor(ScheduleDefinitionRow def, Instant fromExclusive, Instant toInclusive) {
        return occurrencesFor(def, fromExclusive, toInclusive);
    }

    private List<Occurrence> occurrencesFor(ScheduleDefinitionRow def, Instant fromExclusive, Instant toInclusive) {
        List<Occurrence> occurrences = switch (def.type()) {
            case CALENDAR -> engine.calendarOccurrences(
                    CronSpec.parse(def.cronExpression()), ZoneId.of(def.timezone()),
                    fromExclusive, toInclusive, def.dstGapPolicy(), def.dstOverlapPolicy());
            case FIXED_INTERVAL -> engine.intervalOccurrences(
                    def.anchorAt(), def.intervalSeconds(), fromExclusive, toInclusive);
        };
        return applyPauseWindows(occurrences, pauseWindows.findByDefinition(def.id()));
    }

    private List<Occurrence> applyPauseWindows(List<Occurrence> occurrences, List<PauseWindowRow> windows) {
        if (windows.isEmpty()) {
            return occurrences;
        }
        return occurrences.stream().map(occ -> {
            if (occ.skipped() || occ.instant() == null) {
                return occ;
            }
            boolean paused = windows.stream().anyMatch(w -> w.contains(occ.instant()));
            return paused ? occ.paused() : occ;
        }).toList();
    }

    private TriggerInstanceRow toRow(ScheduleDefinitionRow def, Occurrence occ, Instant now) {
        return new TriggerInstanceRow(
                UUID.randomUUID(), def.id(), occ.occurrenceKey(), occ.instant(), occ.sortInstant(),
                occ.zoneId(), occ.localTime().toString(),
                occ.offset() != null ? occ.offset().toString() : null,
                occ.skipped() ? InstanceStatus.SKIPPED : InstanceStatus.PLANNED,
                occ.skipReason(), 0, null, now, now);
    }
}
