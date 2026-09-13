package com.example.scheduler.persistence;

import java.time.Instant;
import java.util.UUID;

/** A pause interval [fromTs, toTs); toTs == null means paused indefinitely. */
public record PauseWindowRow(UUID id, UUID definitionId, Instant fromTs, Instant toTs) {

    public boolean contains(Instant instant) {
        return !instant.isBefore(fromTs) && (toTs == null || instant.isBefore(toTs));
    }
}
