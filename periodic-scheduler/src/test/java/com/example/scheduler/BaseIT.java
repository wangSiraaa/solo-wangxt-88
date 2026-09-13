package com.example.scheduler;

import com.example.scheduler.persistence.InstanceRepository;
import com.example.scheduler.service.LeaseService;
import com.example.scheduler.service.PlannerService;
import com.example.scheduler.service.ScheduleService;
import com.example.scheduler.support.InstanceSetDiff;
import com.example.scheduler.support.MutableClock;
import com.example.scheduler.support.TestClockConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base class for integration tests: H2 database, Quartz ticks disabled (tests drive the
 * planner/dispatcher explicitly), mutable clock injected.
 */
@SpringBootTest(properties = {"spring.quartz.auto-startup=false"})
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
public abstract class BaseIT {

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected ScheduleService scheduleService;

    @Autowired
    protected PlannerService plannerService;

    @Autowired
    protected LeaseService leaseService;

    @Autowired
    protected InstanceRepository instanceRepository;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM execution_lease");
        jdbc.update("DELETE FROM trigger_instance");
        jdbc.update("DELETE FROM pause_window");
        jdbc.update("DELETE FROM schedule_definition");
        jdbc.update("UPDATE fencing_counter SET counter_value = 0 WHERE id = 1");
        clock.setInstant(TestClockConfig.T0);
    }

    /** Creates a schedule via the REST API and returns its id. */
    protected String createSchedule(String json) throws Exception {
        String body = mvc.perform(post("/api/v1/schedules")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    /** Computed future instance set via the preview endpoint. */
    protected List<InstanceSetDiff.Entry> preview(String definitionId, String from, String to) throws Exception {
        String json = mvc.perform(get("/api/v1/schedules/{id}/preview", definitionId)
                        .param("from", from).param("to", to))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return toEntries(json);
    }

    /** Persisted instance set via the instances endpoint. */
    protected List<InstanceSetDiff.Entry> persistedInstances(String definitionId, String from, String to) throws Exception {
        String json = mvc.perform(get("/api/v1/schedules/{id}/instances", definitionId)
                        .param("from", from).param("to", to))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return toEntries(json);
    }

    private List<InstanceSetDiff.Entry> toEntries(String json) throws Exception {
        List<InstanceSetDiff.Entry> out = new ArrayList<>();
        for (JsonNode node : objectMapper.readTree(json)) {
            out.add(InstanceSetDiff.ofJson(node));
        }
        return out;
    }

    protected static Instant instant(String iso) {
        return Instant.parse(iso);
    }
}
