package com.example.scheduler;

import com.example.scheduler.api.dto.CreateScheduleRequest;
import com.example.scheduler.api.dto.LeaseView;
import com.example.scheduler.domain.InstanceStatus;
import com.example.scheduler.domain.ScheduleType;
import com.example.scheduler.error.ConflictException;
import com.example.scheduler.persistence.TriggerInstanceRow;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Failed retries must reuse the instance identity: the instance row keeps its id, only
 * retry_count grows, and the total number of planned instances never increases because
 * of retries.
 */
class RetryIdentityIT extends BaseIT {

    @Test
    void retriesKeepInstanceIdentityAndPlannedCount() {
        UUID defId = scheduleService.create(new CreateScheduleRequest(
                "flaky", ScheduleType.FIXED_INTERVAL, null, null, 3600L,
                clock.instant(), null, null, 2)).id();
        long plannedCount = instanceRepository.countByDefinition(defId);

        clock.advance(Duration.ofSeconds(3700));
        UUID instanceId = instanceRepository.findDue(clock.instant(), 1).get(0).id();

        // attempt 1 fails -> SAME instance back to PLANNED with retry_count = 1
        LeaseView lease1 = leaseService.acquire(instanceId, "node-A", Duration.ofSeconds(30));
        leaseService.fail(instanceId, lease1.fencingToken(), true, Duration.ZERO);
        TriggerInstanceRow after1 = instanceRepository.findById(instanceId).orElseThrow();
        assertEquals(InstanceStatus.PLANNED, after1.status());
        assertEquals(1, after1.retryCount());
        assertEquals(instanceId, after1.id(), "retry must reuse the same instance id");
        assertEquals(plannedCount, instanceRepository.countByDefinition(defId),
                "a retry must not increase the planned instance count");

        // attempt 2 fails -> still the same instance, retry_count = 2 (== maxRetries, still retryable)
        LeaseView lease2 = leaseService.acquire(instanceId, "node-A", Duration.ofSeconds(30));
        leaseService.fail(instanceId, lease2.fencingToken(), true, Duration.ZERO);
        TriggerInstanceRow after2 = instanceRepository.findById(instanceId).orElseThrow();
        assertEquals(InstanceStatus.PLANNED, after2.status());
        assertEquals(2, after2.retryCount());
        assertEquals(plannedCount, instanceRepository.countByDefinition(defId));

        // attempt 3 fails -> retries exhausted -> terminal FAILED, same instance id
        LeaseView lease3 = leaseService.acquire(instanceId, "node-A", Duration.ofSeconds(30));
        leaseService.fail(instanceId, lease3.fencingToken(), true, Duration.ZERO);
        TriggerInstanceRow after3 = instanceRepository.findById(instanceId).orElseThrow();
        assertEquals(InstanceStatus.FAILED, after3.status());
        assertEquals(3, after3.retryCount());
        assertEquals(plannedCount, instanceRepository.countByDefinition(defId),
                "planned count is stable across the whole retry lifecycle");

        // a terminally failed instance cannot be leased again
        assertThrows(ConflictException.class,
                () -> leaseService.acquire(instanceId, "node-A", Duration.ofSeconds(30)));
    }

    @Test
    void retryBackoffDefersReacquisition() {
        UUID defId = scheduleService.create(new CreateScheduleRequest(
                "backoff", ScheduleType.FIXED_INTERVAL, null, null, 3600L,
                clock.instant().plusSeconds(3600), null, null, 3)).id();
        clock.advance(Duration.ofSeconds(3700));
        UUID instanceId = instanceRepository.findDue(clock.instant(), 1).get(0).id();

        LeaseView lease = leaseService.acquire(instanceId, "node-A", Duration.ofSeconds(30));
        leaseService.fail(instanceId, lease.fencingToken(), true, Duration.ofSeconds(600));

        // backoff not elapsed: neither the API nor the dispatcher may pick it up
        assertThrows(ConflictException.class,
                () -> leaseService.acquire(instanceId, "node-A", Duration.ofSeconds(30)));
        assertEquals(0, instanceRepository.findDue(clock.instant(), 10).size());

        clock.advance(Duration.ofSeconds(601));
        assertEquals(1, instanceRepository.findDue(clock.instant(), 10).size());
        LeaseView retried = leaseService.acquire(instanceId, "node-A", Duration.ofSeconds(30));
        assertEquals(instanceId, retried.instanceId());
    }

    @Test
    void nonRetryableFailureGoesStraightToTerminal() {
        UUID defId = scheduleService.create(new CreateScheduleRequest(
                "fatal", ScheduleType.FIXED_INTERVAL, null, null, 3600L,
                clock.instant(), null, null, 3)).id();
        clock.advance(Duration.ofSeconds(3700));
        UUID instanceId = instanceRepository.findDue(clock.instant(), 1).get(0).id();

        LeaseView lease = leaseService.acquire(instanceId, "node-A", Duration.ofSeconds(30));
        leaseService.fail(instanceId, lease.fencingToken(), false, Duration.ZERO);
        assertEquals(InstanceStatus.FAILED, instanceRepository.findById(instanceId).orElseThrow().status());
    }
}
