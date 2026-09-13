package com.example.scheduler.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record HeartbeatRequest(@NotNull Long fencingToken,
                               @NotNull @Positive Long ttlSeconds) {
}
