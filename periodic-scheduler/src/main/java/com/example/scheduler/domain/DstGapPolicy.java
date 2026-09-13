package com.example.scheduler.domain;

/**
 * How a calendar occurrence is treated when its local time does not exist
 * (spring-forward gap, e.g. 02:30 on 2026-03-29 in Europe/Berlin).
 */
public enum DstGapPolicy {
    /** Fire at the first valid instant after the gap (02:30 becomes 03:30 local). */
    SHIFT_FORWARD,
    /** Do not fire; record the occurrence as SKIPPED with reason DST_GAP. */
    SKIP
}
