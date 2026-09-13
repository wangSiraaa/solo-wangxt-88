package com.example.scheduler.error;

/**
 * 409 — the request conflicts with current state: instance already leased, stale fencing
 * token, schedule already paused, instance in a terminal status, ...
 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
