package com.example.scheduler.service;

import com.example.scheduler.api.dto.LeaseView;
import com.example.scheduler.domain.InstanceStatus;
import com.example.scheduler.domain.LeaseStatus;
import com.example.scheduler.error.ConflictException;
import com.example.scheduler.error.NotFoundException;
import com.example.scheduler.persistence.InstanceRepository;
import com.example.scheduler.persistence.LeaseRepository;
import com.example.scheduler.persistence.LeaseRow;
import com.example.scheduler.persistence.ScheduleRepository;
import com.example.scheduler.persistence.TriggerInstanceRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Lease protocol. Exactly one ACTIVE lease can exist per instance:
 *
 * <ul>
 *   <li>{@code execution_lease} has the instance id as PRIMARY KEY — one lease row per instance, ever.</li>
 *   <li>acquire() locks the instance row ({@code SELECT ... FOR UPDATE}) and flips its status
 *       PLANNED → LEASED inside the same transaction; concurrent acquirers serialize on the row
 *       lock and the loser sees a non-PLANNED status → 409.</li>
 *   <li>Every acquisition bumps a monotonic fencing token; completion/heartbeat with a stale
 *       token is rejected, so a node whose lease expired cannot interfere with the new owner.</li>
 * </ul>
 *
 * Retries reuse the instance row (same id, retry_count + 1) — a retry never creates a new
 * instance, so the planned count of a schedule is stable under failures.
 */
@Service
public class LeaseService {

    private final InstanceRepository instances;
    private final LeaseRepository leases;
    private final ScheduleRepository schedules;
    private final Clock clock;

    public LeaseService(InstanceRepository instances, LeaseRepository leases,
                        ScheduleRepository schedules, Clock clock) {
        this.instances = instances;
        this.leases = leases;
        this.schedules = schedules;
        this.clock = clock;
    }

    @Transactional
    public LeaseView acquire(UUID instanceId, String ownerNode, Duration ttl) {
        Instant now = clock.instant();
        TriggerInstanceRow instance = instances.lockById(instanceId)
                .orElseThrow(() -> new NotFoundException("instance " + instanceId + " not found"));
        if (instance.status() != InstanceStatus.PLANNED) {
            throw new ConflictException("instance " + instanceId + " is " + instance.status()
                    + ", not acquirable");
        }
        if (instance.nextRetryAt() != null && instance.nextRetryAt().isAfter(now)) {
            throw new ConflictException("instance " + instanceId + " retries at " + instance.nextRetryAt());
        }
        leases.find(instanceId).ifPresent(existing -> {
            if (existing.activeAt(now)) {
                throw new ConflictException("instance " + instanceId + " already leased by "
                        + existing.ownerNode() + " until " + existing.expiresAt());
            }
        });
        long token = leases.nextFencingToken();
        LeaseRow lease = new LeaseRow(instanceId, ownerNode, token, LeaseStatus.ACTIVE,
                now, now.plus(ttl));
        if (leases.update(lease) == 0) {
            leases.insert(lease);
        }
        int flipped = instances.transitionStatus(instanceId, InstanceStatus.PLANNED, InstanceStatus.LEASED, now);
        if (flipped != 1) {
            // Unreachable while the row lock is held; kept as a defensive assertion.
            throw new IllegalStateException("lost race for instance " + instanceId);
        }
        return LeaseView.of(lease);
    }

    @Transactional
    public LeaseView heartbeat(UUID instanceId, long fencingToken, Duration ttl) {
        Instant now = clock.instant();
        LeaseRow lease = activeLeaseOrThrow(instanceId, fencingToken, now);
        LeaseRow renewed = new LeaseRow(lease.instanceId(), lease.ownerNode(), lease.fencingToken(),
                LeaseStatus.ACTIVE, lease.acquiredAt(), now.plus(ttl));
        leases.update(renewed);
        return LeaseView.of(renewed);
    }

    @Transactional
    public void complete(UUID instanceId, long fencingToken) {
        Instant now = clock.instant();
        activeLeaseOrThrow(instanceId, fencingToken, now);
        leases.release(instanceId);
        int n = instances.transitionStatus(instanceId, InstanceStatus.LEASED, InstanceStatus.SUCCEEDED, now);
        if (n != 1) {
            throw new ConflictException("instance " + instanceId + " is not LEASED");
        }
    }

    /**
     * Mark the attempt failed. While retries remain the SAME instance returns to PLANNED with
     * retry_count + 1 and a backoff; otherwise it becomes terminally FAILED.
     */
    @Transactional
    public void fail(UUID instanceId, long fencingToken, boolean retryable, Duration backoff) {
        Instant now = clock.instant();
        activeLeaseOrThrow(instanceId, fencingToken, now);
        TriggerInstanceRow instance = instances.findById(instanceId)
                .orElseThrow(() -> new NotFoundException("instance " + instanceId + " not found"));
        int maxRetries = schedules.findById(instance.definitionId())
                .map(def -> def.maxRetries())
                .orElse(0);
        leases.release(instanceId);
        if (retryable && instance.retryCount() + 1 <= maxRetries) {
            int n = instances.markRetry(instanceId, now.plus(backoff), now);
            if (n != 1) {
                throw new ConflictException("instance " + instanceId + " is not LEASED");
            }
        } else {
            int n = instances.markFailed(instanceId, now);
            if (n != 1) {
                throw new ConflictException("instance " + instanceId + " is not LEASED");
            }
        }
    }

    /** Requeue instances whose lease died without completion (owner crashed). Same instance id. */
    @Transactional
    public int reapExpiredLeases() {
        return instances.requeueExpiredLeases(clock.instant());
    }

    private LeaseRow activeLeaseOrThrow(UUID instanceId, long fencingToken, Instant now) {
        LeaseRow lease = leases.lockById(instanceId)
                .orElseThrow(() -> new ConflictException("instance " + instanceId + " has no lease"));
        if (lease.status() != LeaseStatus.ACTIVE || lease.fencingToken() != fencingToken) {
            throw new ConflictException("stale or foreign fencing token for instance " + instanceId);
        }
        if (!lease.expiresAt().isAfter(now)) {
            throw new ConflictException("lease for instance " + instanceId
                    + " expired at " + lease.expiresAt());
        }
        return lease;
    }
}
