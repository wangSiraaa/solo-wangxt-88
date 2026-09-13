package com.example.scheduler.domain;

/**
 * How a calendar occurrence is treated when its local time happens twice
 * (autumn overlap, e.g. 02:30 on 2026-10-25 in Europe/Berlin exists at +02:00 and +01:00).
 */
public enum DstOverlapPolicy {
    /** Fire only at the first (earlier UTC) occurrence. Default, matches "fire once" intuition. */
    FIRST,
    /** Fire only at the second (later UTC) occurrence. */
    SECOND,
    /** Fire at both occurrences; the two instances carry distinct occurrence keys. */
    BOTH
}
