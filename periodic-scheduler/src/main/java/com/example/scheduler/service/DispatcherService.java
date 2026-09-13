package com.example.scheduler.service;

import com.example.scheduler.api.dto.LeaseView;
import com.example.scheduler.error.ConflictException;
import com.example.scheduler.exec.TaskHandler;
import com.example.scheduler.persistence.InstanceRepository;
import com.example.scheduler.persistence.TriggerInstanceRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Polls for due instances and executes them under a lease. Any number of dispatcher processes
 * may run against the same database: {@link LeaseService#acquire} guarantees that exactly one
 * of them obtains the lease for a given instance, so each instance executes once at a time.
 */
@Service
public class DispatcherService {

    private static final Logger log = LoggerFactory.getLogger(DispatcherService.class);

    private final LeaseService leaseService;
    private final InstanceRepository instances;
    private final TaskHandler taskHandler;
    private final Executor executor;
    private final Clock clock;
    private final String nodeId;
    private final Duration leaseTtl;
    private final Duration retryBackoff;
    private final int batchSize;

    public DispatcherService(LeaseService leaseService,
                             InstanceRepository instances,
                             TaskHandler taskHandler,
                             @Qualifier("taskExecutor") Executor executor,
                             Clock clock,
                             @Value("${app.node-id}") String nodeId,
                             @Value("${app.dispatcher.lease-ttl:PT30S}") Duration leaseTtl,
                             @Value("${app.dispatcher.retry-backoff:PT10S}") Duration retryBackoff,
                             @Value("${app.dispatcher.batch-size:50}") int batchSize) {
        this.leaseService = leaseService;
        this.instances = instances;
        this.taskHandler = taskHandler;
        this.executor = executor;
        this.clock = clock;
        this.nodeId = nodeId;
        this.leaseTtl = leaseTtl;
        this.retryBackoff = retryBackoff;
        this.batchSize = batchSize;
        log.info("dispatcher of node '{}' ready (leaseTtl={}, batchSize={})", nodeId, leaseTtl, batchSize);
    }

    /** One dispatch round. Returns how many instances were leased by this node. */
    public int dispatchDue() {
        leaseService.reapExpiredLeases();
        Instant now = clock.instant();
        List<TriggerInstanceRow> due = instances.findDue(now, batchSize);
        int leased = 0;
        for (TriggerInstanceRow instance : due) {
            LeaseView lease;
            try {
                lease = leaseService.acquire(instance.id(), nodeId, leaseTtl);
            } catch (ConflictException | com.example.scheduler.error.NotFoundException e) {
                // Another node won the race, the instance is not due yet, or it was deleted
                // between findDue and acquire — all normal under contention.
                continue;
            }
            leased++;
            executor.execute(() -> {
                try {
                    taskHandler.handle(instance.id(), instance.definitionId());
                    leaseService.complete(instance.id(), lease.fencingToken());
                } catch (Exception e) {
                    log.warn("execution of instance {} failed on node {}: {}",
                            instance.id(), nodeId, e.toString());
                    leaseService.fail(instance.id(), lease.fencingToken(), true, retryBackoff);
                }
            });
        }
        return leased;
    }
}
