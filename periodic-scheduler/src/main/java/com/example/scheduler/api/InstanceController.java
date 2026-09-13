package com.example.scheduler.api;

import com.example.scheduler.api.dto.AcquireLeaseRequest;
import com.example.scheduler.api.dto.CompleteRequest;
import com.example.scheduler.api.dto.FailRequest;
import com.example.scheduler.api.dto.HeartbeatRequest;
import com.example.scheduler.api.dto.InstanceView;
import com.example.scheduler.api.dto.LeaseView;
import com.example.scheduler.error.NotFoundException;
import com.example.scheduler.persistence.InstanceRepository;
import com.example.scheduler.service.LeaseService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.UUID;

/**
 * Lease lifecycle for trigger instances. Multiple scheduler processes may compete for the
 * same instance; only one acquire call wins (the others get 409).
 */
@RestController
@RequestMapping("/api/v1/instances")
public class InstanceController {

    private final LeaseService leaseService;
    private final InstanceRepository instances;

    public InstanceController(LeaseService leaseService, InstanceRepository instances) {
        this.leaseService = leaseService;
        this.instances = instances;
    }

    @GetMapping("/{id}")
    public InstanceView get(@PathVariable UUID id) {
        return instances.findWithLease(id)
                .map(InstanceView::of)
                .orElseThrow(() -> new NotFoundException("instance " + id + " not found"));
    }

    @PostMapping("/{id}/lease")
    public LeaseView acquire(@PathVariable UUID id, @Valid @RequestBody AcquireLeaseRequest request) {
        return leaseService.acquire(id, request.ownerNode(), Duration.ofSeconds(request.ttlSeconds()));
    }

    @PostMapping("/{id}/heartbeat")
    public LeaseView heartbeat(@PathVariable UUID id, @Valid @RequestBody HeartbeatRequest request) {
        return leaseService.heartbeat(id, request.fencingToken(), Duration.ofSeconds(request.ttlSeconds()));
    }

    @PostMapping("/{id}/complete")
    public InstanceView complete(@PathVariable UUID id, @Valid @RequestBody CompleteRequest request) {
        leaseService.complete(id, request.fencingToken());
        return get(id);
    }

    /**
     * Fail the attempt. While retries remain, the SAME instance returns to PLANNED
     * (retry_count + 1); the planned instance count never grows because of retries.
     */
    @PostMapping("/{id}/fail")
    public InstanceView fail(@PathVariable UUID id, @Valid @RequestBody FailRequest request) {
        leaseService.fail(id, request.fencingToken(),
                request.retryable() == null || request.retryable(),
                Duration.ofSeconds(request.backoffSeconds() == null ? 0 : request.backoffSeconds()));
        return get(id);
    }
}
