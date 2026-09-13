package com.example.scheduler.persistence;

/** Instance row joined with its (optional) lease row. */
public record InstanceWithLease(TriggerInstanceRow instance, LeaseRow lease) {
}
