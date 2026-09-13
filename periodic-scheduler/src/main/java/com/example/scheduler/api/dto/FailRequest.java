package com.example.scheduler.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record FailRequest(@NotNull Long fencingToken,
                          Boolean retryable,
                          @PositiveOrZero Long backoffSeconds) {
}
