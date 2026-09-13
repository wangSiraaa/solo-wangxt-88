package com.example.scheduler;

import com.example.scheduler.api.dto.CreateScheduleRequest;
import com.example.scheduler.api.dto.LeaseView;
import com.example.scheduler.domain.InstanceStatus;
import com.example.scheduler.domain.ScheduleType;
import com.example.scheduler.error.ConflictException;
import com.example.scheduler.exec.TaskHandler;
import com.example.scheduler.persistence.TriggerInstanceRow;
import com.example.scheduler.service.DispatcherService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Multiple schedulers competing for the same instance must produce exactly one valid lease.
 */
class LeaseConcurrencyIT extends BaseIT {

    /** Creates a schedule whose first instance is due, and returns that instance. */
    private TriggerInstanceRow oneDueInstance() {
        UUID defId = scheduleService.create(new CreateScheduleRequest(
                "contended", ScheduleType.FIXED_INTERVAL, null, null, 3600L,
                clock.instant().plusSeconds(3600), null, null, 3)).id();
        clock.advance(Duration.ofSeconds(3700));
        plannerService.materializeFor(defId);
        List<TriggerInstanceRow> due = instanceRepository.findDue(clock.instant(), 10);
        assertEquals(1, due.size(), "exactly one instance should be due");
        return due.get(0);
    }

    @Test
    void sixteenContendersProduceExactlyOneLease() throws Exception {
        TriggerInstanceRow instance = oneDueInstance();
        int contenders = 16;
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            String node = "node-" + i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    leaseService.acquire(instance.id(), node, Duration.ofSeconds(30));
                    return true;
                } catch (ConflictException e) {
                    return false;
                }
            }));
        }
        ready.await();
        go.countDown();
        int wins = 0;
        for (Future<Boolean> f : futures) {
            if (f.get()) {
                wins++;
            }
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(1, wins, "exactly one contender may hold the lease");
        Integer leaseRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM execution_lease WHERE instance_id = ?",
                Integer.class, instance.id().toString());
        assertEquals(1, leaseRows, "exactly one lease row may exist for the instance");
        assertEquals(InstanceStatus.LEASED, instanceRepository.findById(instance.id()).orElseThrow().status());
    }

    @Test
    void expiredLeaseIsReacquirableAndStaleFencingTokenIsRejected() {
        TriggerInstanceRow instance = oneDueInstance();

        LeaseView first = leaseService.acquire(instance.id(), "node-A", Duration.ofSeconds(30));
        clock.advance(Duration.ofSeconds(31)); // lease of node-A expires
        // the dispatcher's reaper returns the abandoned instance to PLANNED (same identity)
        assertEquals(1, leaseService.reapExpiredLeases());
        LeaseView second = leaseService.acquire(instance.id(), "node-B", Duration.ofSeconds(30));

        assertTrue(second.fencingToken() > first.fencingToken(),
                "fencing tokens must increase across acquisitions");
        assertEquals("node-B", second.ownerNode());

        // the old owner's token is now useless
        assertThrows(ConflictException.class,
                () -> leaseService.complete(instance.id(), first.fencingToken()));
        // the current owner can complete
        leaseService.complete(instance.id(), second.fencingToken());
        assertEquals(InstanceStatus.SUCCEEDED, instanceRepository.findById(instance.id()).orElseThrow().status());
    }

    @Test
    void reaperReturnsCrashedNodesInstanceToPlannedWithSameIdentity() {
        TriggerInstanceRow instance = oneDueInstance();
        leaseService.acquire(instance.id(), "node-A", Duration.ofSeconds(30));
        clock.advance(Duration.ofSeconds(31)); // node-A "crashed", lease expired

        int requeued = leaseService.reapExpiredLeases();
        assertEquals(1, requeued);
        TriggerInstanceRow after = instanceRepository.findById(instance.id()).orElseThrow();
        assertEquals(InstanceStatus.PLANNED, after.status());
        assertEquals(instance.id(), after.id(), "requeue keeps the instance identity");

        LeaseView reacquired = leaseService.acquire(instance.id(), "node-B", Duration.ofSeconds(30));
        assertEquals("node-B", reacquired.ownerNode());
    }

    @Test
    void twoDispatchersRacingExecuteTheInstanceExactlyOnce() throws Exception {
        TriggerInstanceRow instance = oneDueInstance();
        AtomicInteger executions = new AtomicInteger();
        TaskHandler countingHandler = (instanceId, definitionId) -> {
            executions.incrementAndGet();
            Thread.sleep(100); // widen the race window
        };
        ExecutorService pool = Executors.newFixedThreadPool(4);
        DispatcherService nodeA = new DispatcherService(leaseService, instanceRepository,
                countingHandler, pool, clock, "node-A", Duration.ofSeconds(30), Duration.ZERO, 10);
        DispatcherService nodeB = new DispatcherService(leaseService, instanceRepository,
                countingHandler, pool, clock, "node-B", Duration.ofSeconds(30), Duration.ZERO, 10);

        Future<Integer> a = pool.submit(nodeA::dispatchDue);
        Future<Integer> b = pool.submit(nodeB::dispatchDue);
        int leased = a.get() + b.get();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(1, leased, "only one dispatcher may lease the instance");
        assertEquals(1, executions.get(), "the instance must execute exactly once");
        assertEquals(InstanceStatus.SUCCEEDED, instanceRepository.findById(instance.id()).orElseThrow().status());
    }
}
