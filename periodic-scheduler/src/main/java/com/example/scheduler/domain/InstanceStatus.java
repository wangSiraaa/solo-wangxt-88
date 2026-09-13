package com.example.scheduler.domain;

/**
 * Lifecycle of a trigger instance. Retrying a failure moves the SAME instance back to
 * {@link #PLANNED}; it never creates a new instance, so the planned count of a schedule
 * is unaffected by retries.
 */
public enum InstanceStatus {
    /** Materialized, waiting for its UTC due time. */
    PLANNED,
    /** Deliberately not executed (DST gap under SKIP policy, or inside a pause window). */
    SKIPPED,
    /** A scheduler node holds the lease and is executing it. */
    LEASED,
    SUCCEEDED,
    /** Terminal failure (retries exhausted or non-retryable). */
    FAILED
}
