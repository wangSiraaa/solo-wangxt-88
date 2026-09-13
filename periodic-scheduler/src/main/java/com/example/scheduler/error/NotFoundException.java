package com.example.scheduler.error;

/** 404 — the referenced schedule or instance does not exist. */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
