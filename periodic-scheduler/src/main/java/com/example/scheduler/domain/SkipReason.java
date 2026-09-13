package com.example.scheduler.domain;

/** Why an instance was skipped instead of executed. */
public enum SkipReason {
    /** The local wall-clock time does not exist (spring-forward gap) and policy is SKIP. */
    DST_GAP,
    /** The occurrence falls inside a pause window of the schedule. */
    PAUSED
}
