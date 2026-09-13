package com.example.scheduler.persistence;

import com.example.scheduler.domain.LeaseStatus;

import java.time.Instant;
import java.util.UUID;

public record LeaseRow(UUID instanceId,
                       String ownerNode,
                       long fencingToken,
                       LeaseStatus status,
                       Instant acquiredAt,
                       Instant expiresAt) {

    public boolean activeAt(Instant now) {
        return status == LeaseStatus.ACTIVE && expiresAt.isAfter(now);
    }
}
