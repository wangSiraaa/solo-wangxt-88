package com.example.scheduler.api.dto;

import java.time.Instant;

/** Pause request; {@code until == null} pauses indefinitely (until /resume). */
public record PauseRequest(Instant until) {
}
