package com.example.scheduler.error;

/** 400 — the request is invalid (bad cron, mixed schedule fields, bad window, ...). */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}
