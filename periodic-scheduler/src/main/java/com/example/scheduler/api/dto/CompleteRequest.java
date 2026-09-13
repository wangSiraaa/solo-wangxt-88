package com.example.scheduler.api.dto;

import jakarta.validation.constraints.NotNull;

public record CompleteRequest(@NotNull Long fencingToken) {
}
