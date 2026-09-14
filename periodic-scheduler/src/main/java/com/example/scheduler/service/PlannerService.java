package com.example.scheduler.service;

import com.example.scheduler.calendar.CronSpec;
import com.example.scheduler.calendar.Occurrence;
import com.example.scheduler.calendar.OccurrenceEngine;
import com.example.scheduler.domain.InstanceStatus;
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
 * Turns schedule definitions into trigger instances.
 *
 * <p><b>Rolling materialization.</b> Each run materializes at most {@code batch-size}
 * occurrences of a definition and stores how far it got in
 * {@code schedule_definition.materialized_until}. The next run continues from that watermark
 * towards {@code now + horizon}. Any frequency (down to 1 second) is therefore accepted at
 * creation time — the horizon is filled incrementally instead of in one giant transaction.
 *
 * <p><b>Idempotent under concurrency.</b> The occurrence key is stable and
 * {@code (definition_id, occurrence_key)} is unique, so racing planner processes insert
 * nothing twice; the watermark only moves forward (guarded update), so a stale process
 * cannot regress it. A regressed/lagging watermark is harmless anyway: re-computed
 * occurrences are no-ops.
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
    private final int batchSize;

    public PlannerService(ScheduleRepository schedules,
                          InstanceRepository instances,
                          PauseWindowRepository pauseWindows,
                          OccurrenceEngine engine,
                          Clock clock,
                          @Value("${app.planner.horizon:P7D}") Duration horizon,
                          @Value("${app.planner.batch-size:10000}") int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("app.planner.batch-size must be >= 1");
        }
        this.schedules = schedules;
        this.instances = instances;
        this.pauseWindows = pauseWindows;
        this.engine = engine;
        this.clock = clock;
        this.horizon = horizon;
        this.batchSize = batchSize;
    }

    /**
     * Materialize the next batch of occurrences of one definition, advancing its
     * watermark towards {@code now + horizon}. Returns how many rows were inserted.
     */
    @Transactional
    public int materializeFor(UUID definitionId) {
        return schedules.findById(definitionId).map(def -> {
            Instant now = clock.instant();
            Instant target = now.plus(horizon);
            Instant from = def.materializedUntil() != null
                    ? def.materializedUntil()
                    : now.minusSeconds(1);
            if (!from.isBefore(target)) {
                return 0; // horizon already covered
            }
            OccurrenceEngine.OccurrencePage page = computePage(def, from, target);
            List<Occurrence> occurrences = applyPauseWindows(page.occurrences(),
                    pauseWindows.findByDefinition(def.id()));
            int inserted = 0;
            for (Occurrence occ : occurrences) {
                if (instances.insertIgnore(toRow(def, occ, now))) {
                    inserted++;
                }
            }
            Instant watermark = page.truncated()
                    ? page.occurrences().get(page.occurrences().size() - 1).sortInstant()
                    : target;
            schedules.advanceMaterializedUntil(def.id(), watermark, now);
            return inserted;
        }).orElse(0);
    }

    /** Materialize all definitions (one batch each) and reconcile pause windows. */
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
        List<Occurrence> occurrences = switch (def.type()) {
            case CALENDAR -> engine.calendarOccurrences(
                    CronSpec.parse(def.cronExpression()), ZoneId.of(def.timezone()),
                    fromExclusive, toInclusive, def.dstGapPolicy(), def.dstOverlapPolicy());
            case FIXED_INTERVAL -> engine.intervalOccurrences(
                    def.anchorAt(), def.intervalSeconds(), fromExclusive, toInclusive);
        };
        return applyPauseWindows(occurrences, pauseWindows.findByDefinition(def.id()));
    }

    private OccurrenceEngine.OccurrencePage computePage(ScheduleDefinitionRow def, Instant from, Instant to) {
        return switch (def.type()) {
            case CALENDAR -> engine.calendarOccurrencesPage(
                    CronSpec.parse(def.cronExpression()), ZoneId.of(def.timezone()),
                    from, to, def.dstGapPolicy(), def.dstOverlapPolicy(), batchSize);
            case FIXED_INTERVAL -> engine.intervalOccurrencesPage(
                    def.anchorAt(), def.intervalSeconds(), from, to, batchSize);
        };
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
