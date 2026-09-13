package com.example.scheduler.api;

import com.example.scheduler.api.dto.CreateScheduleRequest;
import com.example.scheduler.api.dto.InstanceView;
import com.example.scheduler.api.dto.PauseRequest;
import com.example.scheduler.api.dto.ScheduleView;
import com.example.scheduler.error.BadRequestException;
import com.example.scheduler.service.ScheduleService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/schedules")
public class ScheduleController {

    private final ScheduleService service;

    public ScheduleController(ScheduleService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ScheduleView create(@Valid @RequestBody CreateScheduleRequest request) {
        return service.create(request);
    }

    @GetMapping("/{id}")
    public ScheduleView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping
    public List<ScheduleView> list() {
        return service.list();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    @PostMapping("/{id}/pause")
    public ScheduleView pause(@PathVariable UUID id, @RequestBody(required = false) PauseRequest request) {
        return service.pause(id, request != null ? request.until() : null);
    }

    @PostMapping("/{id}/resume")
    public ScheduleView resume(@PathVariable UUID id) {
        return service.resume(id);
    }

    /**
     * Computed future instance set over a window: original timezone, UTC trigger times and
     * skip reasons — without persisting anything.
     */
    @GetMapping("/{id}/preview")
    public List<InstanceView> preview(@PathVariable UUID id,
                                      @RequestParam String from,
                                      @RequestParam String to) {
        return service.preview(id, parseInstant("from", from), parseInstant("to", to));
    }

    /** Persisted instance set over a window (including lease information). */
    @GetMapping("/{id}/instances")
    public List<InstanceView> instances(@PathVariable UUID id,
                                        @RequestParam String from,
                                        @RequestParam String to) {
        return service.instances(id, parseInstant("from", from), parseInstant("to", to));
    }

    static Instant parseInstant(String param, String value) {
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            throw new BadRequestException("parameter '" + param + "' must be an ISO-8601 instant, got '" + value + "'");
        }
    }
}
