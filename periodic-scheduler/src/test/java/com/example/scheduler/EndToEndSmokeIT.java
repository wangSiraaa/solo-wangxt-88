package com.example.scheduler;

import com.example.scheduler.api.dto.CreateScheduleRequest;
import com.example.scheduler.domain.ScheduleType;
import com.example.scheduler.service.ScheduleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end wiring check with Quartz ticks enabled and the real system clock: a created
 * schedule must be planned, leased and executed to SUCCEEDED by the Quartz-driven
 * planner/dispatcher jobs — no test intervention beyond creation.
 */
@SpringBootTest(properties = {
        "spring.quartz.auto-startup=true",
        "app.planner.tick=PT1S",
        "app.planner.horizon=PT30S",
        "app.dispatcher.tick=PT0.5S",
        "app.dispatcher.lease-ttl=PT10S",
        "app.dispatcher.retry-backoff=PT1S"})
class EndToEndSmokeIT {

    @Autowired
    private ScheduleService scheduleService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM execution_lease");
        jdbc.update("DELETE FROM trigger_instance");
        jdbc.update("DELETE FROM pause_window");
        jdbc.update("DELETE FROM schedule_definition");
    }

    @Test
    void quartzTicksDriveAnInstanceToSuccess() throws Exception {
        UUID defId = scheduleService.create(new CreateScheduleRequest(
                "smoke", ScheduleType.FIXED_INTERVAL, null, null, 2L, null, null, null, 1)).id();

        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            Integer succeeded = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM trigger_instance WHERE definition_id = ? AND status = 'SUCCEEDED'",
                    Integer.class, defId.toString());
            if (succeeded != null && succeeded >= 1) {
                Integer activeLeasesOnFinished = jdbc.queryForObject("""
                                SELECT COUNT(*) FROM execution_lease el
                                JOIN trigger_instance ti ON ti.id = el.instance_id
                                WHERE ti.definition_id = ? AND ti.status = 'SUCCEEDED' AND el.status = 'ACTIVE'""",
                        Integer.class, defId.toString());
                assertEquals(0, activeLeasesOnFinished, "completed instances must not keep an active lease");
                System.out.println("smoke: " + succeeded + " instance(s) SUCCEEDED via Quartz ticks");
                return;
            }
            Thread.sleep(200);
        }
        fail("no instance reached SUCCEEDED within 30s — planner/dispatcher wiring broken");
    }
}
