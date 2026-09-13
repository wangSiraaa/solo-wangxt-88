package com.example.scheduler;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The two schedule kinds must stay strictly separated at the API boundary. */
class ApiValidationIT extends BaseIT {

    @Test
    void calendarMustNotCarryIntervalFields() throws Exception {
        mvc.perform(post("/api/v1/schedules").contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"x","type":"CALENDAR","cron":"0 0 9 * * *","timezone":"Europe/Berlin","intervalSeconds":60}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("FIXED_INTERVAL")));
    }

    @Test
    void intervalMustNotCarryCalendarFields() throws Exception {
        mvc.perform(post("/api/v1/schedules").contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"x","type":"FIXED_INTERVAL","intervalSeconds":60,"cron":"0 0 9 * * *"}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("CALENDAR")));
        mvc.perform(post("/api/v1/schedules").contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"x","type":"FIXED_INTERVAL","intervalSeconds":60,"timezone":"Europe/Berlin"}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("CALENDAR")));
    }

    @Test
    void calendarRequiresCronAndTimezone() throws Exception {
        mvc.perform(post("/api/v1/schedules").contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"x","type":"CALENDAR","timezone":"Europe/Berlin"}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("cron")));
        mvc.perform(post("/api/v1/schedules").contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"x","type":"CALENDAR","cron":"0 0 9 * * *"}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("timezone")));
    }

    @Test
    void invalidCronTimezoneAndIntervalAreRejected() throws Exception {
        mvc.perform(post("/api/v1/schedules").contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"x","type":"CALENDAR","cron":"0 0 25 * * *","timezone":"Europe/Berlin"}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("invalid cron")));
        mvc.perform(post("/api/v1/schedules").contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"x","type":"CALENDAR","cron":"0 0 9 * * *","timezone":"Mars/Olympus_Mons"}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("unknown timezone")));
        mvc.perform(post("/api/v1/schedules").contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"x","type":"FIXED_INTERVAL","intervalSeconds":0}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("intervalSeconds")));
    }

    @Test
    void createGetAndUnknownId() throws Exception {
        String id = createSchedule("""
                {"name":"daily","type":"CALENDAR","cron":"0 0 9 * * *","timezone":"Europe/Berlin"}
                """);
        mvc.perform(get("/api/v1/schedules/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("CALENDAR"))
                .andExpect(jsonPath("$.timezone").value("Europe/Berlin"))
                .andExpect(jsonPath("$.dstGapPolicy").value("SHIFT_FORWARD"))
                .andExpect(jsonPath("$.dstOverlapPolicy").value("FIRST"))
                .andExpect(jsonPath("$.paused").value(false));
        mvc.perform(get("/api/v1/schedules/{id}", "00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound());
    }

    @Test
    void windowValidation() throws Exception {
        String id = createSchedule("""
                {"name":"daily","type":"CALENDAR","cron":"0 0 9 * * *","timezone":"Europe/Berlin"}
                """);
        mvc.perform(get("/api/v1/schedules/{id}/preview", id)
                        .param("from", "2026-09-14T00:00:00Z").param("to", "2026-09-13T00:00:00Z"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/schedules/{id}/preview", id)
                        .param("from", "not-an-instant").param("to", "2026-09-13T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("ISO-8601")));
    }

    @Test
    void leaseEndpointValidation() throws Exception {
        mvc.perform(post("/api/v1/instances/{id}/lease", "00000000-0000-0000-0000-000000000000")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerNode\":\"node-1\",\"ttlSeconds\":30}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/instances/{id}/lease", "00000000-0000-0000-0000-000000000000")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ttlSeconds\":30}"))
                .andExpect(status().isBadRequest());
    }
}
