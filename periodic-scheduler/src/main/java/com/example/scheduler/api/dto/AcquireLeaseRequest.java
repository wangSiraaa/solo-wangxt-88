package com.example.scheduler.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AcquireLeaseRequest(@NotBlank String ownerNode,
                                  @NotNull @Positive Long ttlSeconds) {
}
