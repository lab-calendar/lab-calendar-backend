package com.labcalendar.labcalendarbackend;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import jakarta.servlet.http.Cookie;
import com.labcalendar.labcalendarbackend.auth.AuthCookie;
import com.labcalendar.labcalendarbackend.auth.AuthTier;
import com.labcalendar.labcalendarbackend.auth.token.AuthTokenCodec;
import com.labcalendar.labcalendarbackend.config.TimeConfig;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** KAN-50: server date, imminent boundaries and dashboard ordering through the real API. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(ProjectDeadlineApiTests.FixedTime.class)
class ProjectDeadlineApiTests {
    @Autowired MockMvc mvc;
    @Autowired AuthTokenCodec tokens;
    @Autowired MutableClock clock;

    @BeforeEach
    void resetClock() {
        clock.now = Instant.parse("2026-09-25T15:00:00Z"); // September 26 in Seoul, 25 in UTC
    }

    private Cookie session() {
        return new Cookie(AuthCookie.NAME, tokens.issue(AuthTier.EDITOR).value());
    }

    private String body(String name, String deadline, boolean active) {
        return """
                {"name":"%s","endDate":"%s","leadTimeDays":21,"active":%b}
                """.formatted(name, deadline, active);
    }

    private String create(String name, String deadline, boolean active, int days, boolean imminent)
            throws Exception {
        String response = mvc.perform(post("/api/projects").cookie(session())
                        .contentType("application/json").content(body(name, deadline, active)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.dDay").value(days))
                .andExpect(jsonPath("$.data.deadlineImminent").value(imminent))
                .andReturn().getResponse().getContentAsString();
        return response.replaceAll(".*\"id\"\\s*:\\s*\"(\\d+)\".*", "$1");
    }

    @Test
    void listsDeadlineBoundariesInOrderAndRetainsOverdueProjects() throws Exception {
        create("eight", "2026-10-04", true, 8, false);
        create("hidden", "2026-09-26", false, 0, false);
        create("seven", "2026-10-03", true, 7, true);
        create("overdue", "2026-09-25", true, -1, false);
        create("today", "2026-09-26", true, 0, true);

        mvc.perform(get("/api/projects").cookie(session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].name", contains("overdue", "today", "seven", "eight", "hidden")))
                .andExpect(jsonPath("$.data[*].dDay", contains(-1, 0, 7, 8, 0)))
                .andExpect(jsonPath("$.data[*].deadlineImminent", contains(false, true, true, false, false)));
    }

    @Test
    void recomputesAtSeoulMidnightWithoutUpdatingTheProject() throws Exception {
        clock.now = Instant.parse("2026-09-25T14:59:59Z");
        String id = create("boundary", "2026-10-03", true, 8, false);
        clock.now = Instant.parse("2026-09-25T15:00:00Z");

        mvc.perform(get("/api/projects/" + id).cookie(session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dDay").value(7))
                .andExpect(jsonPath("$.data.deadlineImminent").value(true));
    }

    @Test
    void updatesFlagWhenDeadlineOrActiveStateChanges() throws Exception {
        String id = create("project", "2026-10-04", true, 8, false);
        mvc.perform(put("/api/projects/" + id).cookie(session()).contentType("application/json")
                        .content(body("project", "2026-10-03", true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deadlineImminent").value(true));
        mvc.perform(put("/api/projects/" + id).cookie(session()).contentType("application/json")
                        .content(body("project", "2026-10-03", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deadlineImminent").value(false));
    }

    @TestConfiguration
    static class FixedTime {
        @Bean @Primary
        MutableClock deadlineClock() { return new MutableClock(); }
    }

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-25T15:00:00Z");
        @Override public ZoneId getZone() { return TimeConfig.SERVICE_ZONE; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}
