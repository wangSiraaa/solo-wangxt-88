package com.example.scheduler.calendar;

import com.example.scheduler.domain.DstGapPolicy;
import com.example.scheduler.domain.DstOverlapPolicy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Computes the occurrences of a schedule over a half-open UTC window
 * {@code (fromExclusive, toInclusive]}.
 *
 * <p>Calendar occurrences are resolved against {@link ZoneRules} so DST gaps and overlaps are
 * handled explicitly according to the definition's policies, and skipped occurrences are
 * reported with a reason instead of silently disappearing.
 *
 * <p>Fixed-interval occurrences are pure arithmetic on the UTC timeline
 * ({@code anchor + k * interval}); wall-clock rules never apply.
 */
public final class OccurrenceEngine {

    /** Safety bound for interval materialization windows. */
    public static final int MAX_INTERVAL_OCCURRENCES = 1_000_000;

    public List<Occurrence> calendarOccurrences(CronSpec spec, ZoneId zone,
                                                Instant fromExclusive, Instant toInclusive,
                                                DstGapPolicy gapPolicy, DstOverlapPolicy overlapPolicy) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(zone, "zone");
        if (!toInclusive.isAfter(fromExclusive)) {
            return List.of();
        }
        ZoneRules rules = zone.getRules();
        LocalDate startDay = fromExclusive.atZone(zone).toLocalDate();
        LocalDate endDay = toInclusive.atZone(zone).toLocalDate();
        List<Occurrence> out = new ArrayList<>();
        int[] timesOfDay = spec.secondsOfDay();
        for (LocalDate day = startDay; !day.isAfter(endDay); day = day.plusDays(1)) {
            if (!spec.matchesDay(day)) {
                continue;
            }
            for (int tod : timesOfDay) {
                LocalDateTime wallTime = LocalDateTime.of(day, LocalTime.ofSecondOfDay(tod));
                List<ZoneOffset> offsets = rules.getValidOffsets(wallTime);
                if (offsets.isEmpty()) {
                    addGapOccurrence(out, rules, wallTime, zone, gapPolicy, fromExclusive, toInclusive);
                } else if (offsets.size() == 2) {
                    addOverlapOccurrences(out, wallTime, zone, offsets, overlapPolicy, fromExclusive, toInclusive);
                } else {
                    ZonedDateTime z = ZonedDateTime.ofLocal(wallTime, zone, offsets.get(0));
                    if (inWindow(z.toInstant(), fromExclusive, toInclusive)) {
                        out.add(new Occurrence(z.toInstant(), wallTime, offsets.get(0), zone.getId(),
                                null, wallTime + "|" + offsets.get(0), z.toInstant()));
                    }
                }
            }
        }
        out.sort(Comparator.comparing(Occurrence::sortInstant).thenComparing(Occurrence::occurrenceKey));
        return out;
    }

    /** Local time falls in a spring-forward gap: it does not exist. */
    private void addGapOccurrence(List<Occurrence> out, ZoneRules rules, LocalDateTime wallTime, ZoneId zone,
                                  DstGapPolicy gapPolicy, Instant fromExclusive, Instant toInclusive) {
        ZoneOffsetTransition transition = rules.getTransition(wallTime);
        if (gapPolicy == DstGapPolicy.SKIP) {
            Instant gapStart = transition.getInstant();
            if (inWindow(gapStart, fromExclusive, toInclusive)) {
                out.add(new Occurrence(null, wallTime, null, zone.getId(),
                        com.example.scheduler.domain.SkipReason.DST_GAP, wallTime + "|GAP", gapStart));
            }
        } else {
            // SHIFT_FORWARD: ZonedDateTime semantics — move forward by the gap length
            // (02:30 in a 1-hour gap becomes 03:30 local).
            ZonedDateTime shifted = ZonedDateTime.ofLocal(wallTime, zone, null);
            if (inWindow(shifted.toInstant(), fromExclusive, toInclusive)) {
                out.add(new Occurrence(shifted.toInstant(), shifted.toLocalDateTime(), shifted.getOffset(),
                        zone.getId(), null, wallTime + "|FWD", shifted.toInstant()));
            }
        }
    }

    /** Local time falls in an autumn overlap: it exists twice, once per offset. */
    private void addOverlapOccurrences(List<Occurrence> out, LocalDateTime wallTime, ZoneId zone,
                                       List<ZoneOffset> offsets, DstOverlapPolicy overlapPolicy,
                                       Instant fromExclusive, Instant toInclusive) {
        // getValidOffsets returns [offset-before-transition, offset-after-transition];
        // index 0 is therefore the FIRST (earlier UTC) occurrence.
        List<ZoneOffset> chosen = switch (overlapPolicy) {
            case FIRST -> List.of(offsets.get(0));
            case SECOND -> List.of(offsets.get(1));
            case BOTH -> offsets;
        };
        for (ZoneOffset offset : chosen) {
            ZonedDateTime z = ZonedDateTime.ofLocal(wallTime, zone, offset);
            if (inWindow(z.toInstant(), fromExclusive, toInclusive)) {
                out.add(new Occurrence(z.toInstant(), wallTime, offset, zone.getId(),
                        null, wallTime + "|" + offset, z.toInstant()));
            }
        }
    }

    /**
     * Occurrences of a fixed-interval schedule: {@code anchor + k * intervalSeconds} for
     * integer k, strictly after {@code fromExclusive}, up to and including {@code toInclusive}.
     * The anchor is truncated to whole seconds.
     */
    public List<Occurrence> intervalOccurrences(Instant anchor, long intervalSeconds,
                                                Instant fromExclusive, Instant toInclusive) {
        if (intervalSeconds <= 0) {
            throw new IllegalArgumentException("intervalSeconds must be > 0");
        }
        if (!toInclusive.isAfter(fromExclusive)) {
            return List.of();
        }
        long anchorEpoch = anchor.getEpochSecond();
        long fromEpoch = fromExclusive.getEpochSecond();
        long toEpoch = toInclusive.getEpochSecond();
        long firstK = anchorEpoch > fromEpoch ? 0 : (fromEpoch - anchorEpoch) / intervalSeconds + 1;
        List<Occurrence> out = new ArrayList<>();
        for (long k = firstK; ; k++) {
            long t = anchorEpoch + k * intervalSeconds;
            if (t > toEpoch) {
                break;
            }
            Instant instant = Instant.ofEpochSecond(t);
            ZonedDateTime z = instant.atZone(ZoneOffset.UTC);
            out.add(new Occurrence(instant, z.toLocalDateTime(), ZoneOffset.UTC, "UTC",
                    null, "I|" + instant, instant));
            if (out.size() > MAX_INTERVAL_OCCURRENCES) {
                throw new IllegalArgumentException("interval schedule produces more than "
                        + MAX_INTERVAL_OCCURRENCES + " occurrences in the requested window");
            }
        }
        return out;
    }

    private static boolean inWindow(Instant instant, Instant fromExclusive, Instant toInclusive) {
        return instant.isAfter(fromExclusive) && !instant.isAfter(toInclusive);
    }
}
