package com.labcalendar.labcalendarbackend;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Range listing (KAN-40).
 *
 * <p>The fixtures straddle September 2026 on purpose: a schedule that begins in August and ends in
 * October still belongs on the September calendar.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EventRangeApiTests {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private static final String SEPTEMBER = "?from=2026-09-01&to=2026-09-30";

    @BeforeEach
    void seedEvents() {
        insert("8월에 끝남", "lab", "2026-08-20", "2026-08-31");
        insert("8월에 시작해 9월까지", "lab", "2026-08-28", "2026-09-02");
        insert("9월 한가운데", "project", "2026-09-10", "2026-09-10");
        insert("9월 말에 시작해 10월까지", "project", "2026-09-29", "2026-10-03");
        insert("10월에 시작", "card", "2026-10-01", "2026-10-05");
        insert("8월부터 10월까지 통째로", "card", "2026-08-01", "2026-10-31");
    }

    @Test
    void includesEveryEventOverlappingTheMonth() throws Exception {
        mvc.perform(get("/api/events" + SEPTEMBER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(4)))
                .andExpect(jsonPath("$.data[*].title", contains(
                        "8월부터 10월까지 통째로",
                        "8월에 시작해 9월까지",
                        "9월 한가운데",
                        "9월 말에 시작해 10월까지")));
    }

    @Test
    void excludesEventsOutsideTheRange() throws Exception {
        mvc.perform(get("/api/events" + SEPTEMBER))
                .andExpect(jsonPath("$.data[?(@.title == '8월에 끝남')]").isEmpty())
                .andExpect(jsonPath("$.data[?(@.title == '10월에 시작')]").isEmpty());
    }

    @Test
    void bothEndsOfTheRangeAreInclusive() throws Exception {
        // An event starting exactly on `to` is in range.
        mvc.perform(get("/api/events?from=2026-09-20&to=2026-09-29"))
                .andExpect(jsonPath("$.data[?(@.title == '9월 말에 시작해 10월까지')]").isNotEmpty());

        // An event ending exactly on `from` is in range.
        mvc.perform(get("/api/events?from=2026-09-02&to=2026-09-05"))
                .andExpect(jsonPath("$.data[?(@.title == '8월에 시작해 9월까지')]").isNotEmpty());

        // A single day queried as from == to.
        mvc.perform(get("/api/events?from=2026-09-10&to=2026-09-10"))
                .andExpect(jsonPath("$.data[?(@.title == '9월 한가운데')]").isNotEmpty());
    }

    @Test
    void filtersByASingleCategory() throws Exception {
        mvc.perform(get("/api/events" + SEPTEMBER + "&categories=project"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[*].categoryKey", contains("project", "project")));
    }

    @Test
    void filtersBySeveralCategories() throws Exception {
        mvc.perform(get("/api/events" + SEPTEMBER + "&categories=project,card"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data[?(@.categoryKey == 'lab')]").isEmpty());
    }

    @Test
    void emptySelectionReturnsNothingRatherThanEverything() throws Exception {
        mvc.perform(get("/api/events" + SEPTEMBER + "&categories="))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    void unknownCategoryKeyIsRejected() throws Exception {
        mvc.perform(get("/api/events" + SEPTEMBER + "&categories=RESEARCH"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reversedRangeIsRejected() throws Exception {
        mvc.perform(get("/api/events?from=2026-09-30&to=2026-09-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void missingRangeIsRejected() throws Exception {
        mvc.perform(get("/api/events?from=2026-09-01")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/events")).andExpect(status().isBadRequest());
    }

    @Test
    void malformedDateIsRejected() throws Exception {
        mvc.perform(get("/api/events?from=2026-13-01&to=2026-09-30"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void emptyRangeReturnsAnEmptyList() throws Exception {
        mvc.perform(get("/api/events?from=2027-01-01&to=2027-01-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    void listCarriesParticipantsInStoredOrder() throws Exception {
        Long eventId = jdbc.queryForObject(
                "SELECT id FROM event WHERE title = '9월 한가운데'", Long.class);
        insertParticipant(eventId, "홍길동", 0);
        insertParticipant(eventId, "김철수", 1);

        mvc.perform(get("/api/events?from=2026-09-10&to=2026-09-10"))
                .andExpect(jsonPath("$.data[0].participants", contains("홍길동", "김철수")));
    }

    @Test
    void keepsGeneratedEventsWhileTheirProjectIsActive() throws Exception {
        insertProjectEvent("살아있는 과제", true, "2026-09-05", "2026-09-26");

        mvc.perform(get("/api/events" + SEPTEMBER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.title == '살아있는 과제')]").isNotEmpty());
    }

    @Test
    void dropsGeneratedEventsOnceTheirProjectIsHidden() throws Exception {
        insertProjectEvent("숨긴 과제", false, "2026-09-05", "2026-09-26");

        // 과제를 비활성으로 두는 것이 준비 기간을 달력에서 내리는 방법이다 (계약 §7.3).
        mvc.perform(get("/api/events" + SEPTEMBER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.title == '숨긴 과제')]").isEmpty());
    }

    @Test
    void theSameRuleAppliesWhenFilteringByCategory() throws Exception {
        insertProjectEvent("살아있는 과제", true, "2026-09-05", "2026-09-26");
        insertProjectEvent("숨긴 과제", false, "2026-09-06", "2026-09-27");

        mvc.perform(get("/api/events" + SEPTEMBER + "&categories=project"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.title == '살아있는 과제')]").isNotEmpty())
                .andExpect(jsonPath("$.data[?(@.title == '숨긴 과제')]").isEmpty());
    }

    @Test
    void hidingAProjectDoesNotTouchManualEvents() throws Exception {
        insertProjectEvent("숨긴 과제", false, "2026-09-05", "2026-09-26");

        // 수동 일정은 과제와 무관하므로 그대로 남아야 한다.
        mvc.perform(get("/api/events" + SEPTEMBER))
                .andExpect(jsonPath("$.data", hasSize(4)))
                .andExpect(jsonPath("$.data[?(@.title == '9월 한가운데')]").isNotEmpty());
    }

    /**
     * A project with the schedule the batch would generate for it (KAN-49).
     *
     * <p>Written straight to the tables because nothing creates these through the app yet.
     */
    private void insertProjectEvent(String name, boolean active, String startDate, String endDate) {
        jdbc.update("""
                INSERT INTO research_project (name, submission_type, end_date, lead_time_days,
                        active, created_at, updated_at)
                VALUES (?, '연차보고서', ?, 21, ?, '2026-09-01 00:00:00', '2026-09-01 00:00:00')
                """, name, endDate, active);
        Long projectId = jdbc.queryForObject(
                "SELECT id FROM research_project WHERE name = ?", Long.class, name);
        Long categoryId = jdbc.queryForObject(
                "SELECT id FROM category WHERE code = 'project'", Long.class);

        jdbc.update("""
                INSERT INTO event (category_id, research_project_id, title, start_date, end_date,
                        all_day, source, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, TRUE, 'AUTO_GENERATED', '2026-09-01 00:00:00',
                        '2026-09-01 00:00:00')
                """, categoryId, projectId, name, startDate, endDate);
    }

    private void insert(String title, String categoryKey, String startDate, String endDate) {
        Long categoryId = jdbc.queryForObject(
                "SELECT id FROM category WHERE code = ?", Long.class, categoryKey);
        jdbc.update("""
                INSERT INTO event (category_id, title, start_date, end_date, all_day, source,
                        created_at, updated_at)
                VALUES (?, ?, ?, ?, TRUE, 'MANUAL', '2026-09-01 00:00:00', '2026-09-01 00:00:00')
                """, categoryId, title, startDate, endDate);
    }

    private void insertParticipant(Long eventId, String name, int position) {
        jdbc.update("""
                INSERT INTO event_participant (event_id, display_name, position, created_at, updated_at)
                VALUES (?, ?, ?, '2026-09-01 00:00:00', '2026-09-01 00:00:00')
                """, eventId, name, position);
    }
}
