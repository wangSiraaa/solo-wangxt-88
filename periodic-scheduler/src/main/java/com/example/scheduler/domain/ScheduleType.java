package com.example.scheduler.domain;

/**
 * The two scheduling rules this service keeps strictly separate:
 *
 * <ul>
 *   <li>{@link #FIXED_INTERVAL} — "every N seconds from an anchor instant". Defined purely on the
 *       UTC timeline; DST and wall-clock shifts are irrelevant. "Every 24 hours" fires exactly
 *       86_400 seconds after the previous fire, even across a DST transition.</li>
 *   <li>{@link #CALENDAR} — "9:00 local time every day" (cron + IANA timezone). Defined on the
 *       wall clock of a named zone; the UTC instant moves when the zone's offset changes.</li>
 * </ul>
 */
public enum ScheduleType {
    FIXED_INTERVAL,
    CALENDAR
}
