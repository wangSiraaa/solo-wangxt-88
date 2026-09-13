package com.example.scheduler.api.dto;

import java.time.Instant;
import java.util.UUID;

public record LeaseView(UUID instanceId,
                        String ownerNode,
                        long fencingToken,
                        String status,
                        Instant acquiredAt,
                        Instant expiresAt) {

    public static LeaseView of(com.example.scheduler.persistence.LeaseRow row) {
        return new LeaseView(row.instanceId(), row.ownerNode(), row.fencingToken(),
                row.status().name(), row.acquiredAt(), row.expiresAt());
    }
}
