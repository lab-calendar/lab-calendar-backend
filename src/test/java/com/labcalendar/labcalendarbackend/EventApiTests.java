package com.labcalendar.labcalendarbackend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import com.labcalendar.labcalendarbackend.event.repository.EventParticipantRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Event CRUD against the real schema (KAN-39).
 *
 * <p>Rolled back per test so the migration suite still sees empty tables.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EventApiTests {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired EventParticipantRepository participants;

    private static final String LAB_MEETING = """
            {"title":"정기 주간 랩미팅","detail":"홍길동","startDate":"2026-09-10",
             "endDate":"2026-09-10","categoryKey":"lab","memo":"주간 진행 공유",
             "participants":["홍길동","김철수"]}
            """;

    @Test
    void createsSingleDayEvent() throws Exception {
        mvc.perform(post("/api/events").contentType("application/json").content(LAB_MEETING))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").exists())
                .andExpect(jsonPath("$.data.title").value("정기 주간 랩미팅"))
                .andExpect(jsonPath("$.data.detail").value("홍길동"))
                .andExpect(jsonPath("$.data.startDate").value("2026-09-10"))
                .andExpect(jsonPath("$.data.endDate").value("2026-09-10"))
                .andExpect(jsonPath("$.data.categoryKey").value("lab"))
                .andExpect(jsonPath("$.data.source").value("MANUAL"))
                .andExpect(jsonPath("$.data.participants", contains("홍길동", "김철수")));
    }

    @Test
    void createsMultiDayEventKeepingBothEndsInclusive() throws Exception {
        String id = create("""
                {"title":"학술대회 참석","startDate":"2026-09-08","endDate":"2026-09-11",
                 "categoryKey":"project","participants":[]}
                """);

        mvc.perform(get("/api/events/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.startDate").value("2026-09-08"))
                .andExpect(jsonPath("$.data.endDate").value("2026-09-11"));
    }

    @Test
    void readsBackMemoAndParticipants() throws Exception {
        String id = create(LAB_MEETING);

        mvc.perform(get("/api/events/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memo").value("주간 진행 공유"))
                .andExpect(jsonPath("$.data.participants", hasSize(2)));
    }

    @Test
    void updatesFieldsAndReplacesParticipants() throws Exception {
        String id = create(LAB_MEETING);

        mvc.perform(put("/api/events/" + id).contentType("application/json").content("""
                        {"title":"격주 랩미팅","detail":"김철수","startDate":"2026-09-14",
                         "endDate":"2026-09-15","categoryKey":"lab","memo":null,
                         "participants":["이영희"]}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("격주 랩미팅"))
                .andExpect(jsonPath("$.data.detail").value("김철수"))
                .andExpect(jsonPath("$.data.endDate").value("2026-09-15"))
                .andExpect(jsonPath("$.data.memo").doesNotExist())
                .andExpect(jsonPath("$.data.participants", contains("이영희")));
    }

    @Test
    void deleteRemovesEventAndItsParticipants() throws Exception {
        String id = create(LAB_MEETING);

        mvc.perform(delete("/api/events/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/events/" + id)).andExpect(status().isNotFound());
        // Queried through JPA so the pending deletes are flushed first; a raw JDBC count would
        // run on the same transaction but miss what the persistence context has not written yet.
        assertThat(participants.findByEventIdOrderByPositionAsc(Long.valueOf(id))).isEmpty();
    }

    @Test
    void rejectsEndDateBeforeStartDate() throws Exception {
        mvc.perform(post("/api/events").contentType("application/json").content("""
                        {"title":"거꾸로","startDate":"2026-09-10","endDate":"2026-09-09",
                         "categoryKey":"lab","participants":[]}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.endDate").exists());
    }

    @Test
    void rejectsBlankTitle() throws Exception {
        mvc.perform(post("/api/events").contentType("application/json").content("""
                        {"title":"   ","startDate":"2026-09-10","endDate":"2026-09-10",
                         "categoryKey":"lab","participants":[]}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.title").exists());
    }

    @Test
    void rejectsUnknownCategoryKey() throws Exception {
        mvc.perform(post("/api/events").contentType("application/json").content("""
                        {"title":"분류 없음","startDate":"2026-09-10","endDate":"2026-09-10",
                         "categoryKey":"RESEARCH","participants":[]}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void trimsBlankAndDuplicateParticipantNames() throws Exception {
        mvc.perform(post("/api/events").contentType("application/json").content("""
                        {"title":"정리 확인","startDate":"2026-09-10","endDate":"2026-09-10",
                         "categoryKey":"lab","participants":["  홍길동 ","","홍길동","김철수"]}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.participants", contains("홍길동", "김철수")));
    }

    @Test
    void missingEventIsNotFound() throws Exception {
        mvc.perform(get("/api/events/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void generatedEventShowsTitleAndDetailFromItsProject() throws Exception {
        String id = insertGeneratedEvent();

        mvc.perform(get("/api/events/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("BRL 과제"))
                .andExpect(jsonPath("$.data.detail").value("연차보고서"))
                .andExpect(jsonPath("$.data.source").value("AUTO_GENERATED"));
    }

    @Test
    void generatedEventRejectsUpdateAndDelete() throws Exception {
        String id = insertGeneratedEvent();

        mvc.perform(put("/api/events/" + id).contentType("application/json").content("""
                        {"title":"손대기","startDate":"2026-09-05","endDate":"2026-09-26",
                         "categoryKey":"project","participants":[]}
                        """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mvc.perform(delete("/api/events/" + id)).andExpect(status().isForbidden());
    }

    private String create(String body) throws Exception {
        String response = mvc.perform(post("/api/events").contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return response.replaceAll(".*\"id\"\\s*:\\s*\"(\\d+)\".*", "$1");
    }

    /** Written straight to the tables: only the batch (KAN-49) creates these through the app. */
    private String insertGeneratedEvent() {
        jdbc.update("""
                INSERT INTO research_project (name, submission_type, end_date, lead_time_days, active,
                        created_at, updated_at)
                VALUES ('BRL 과제', '연차보고서', '2026-09-26', 21, TRUE, '2026-09-01 00:00:00',
                        '2026-09-01 00:00:00')
                """);
        Long projectId = jdbc.queryForObject(
                "SELECT id FROM research_project WHERE name = 'BRL 과제'", Long.class);
        Long categoryId = jdbc.queryForObject(
                "SELECT id FROM category WHERE code = 'project'", Long.class);

        jdbc.update("""
                INSERT INTO event (category_id, research_project_id, title, start_date, end_date,
                        all_day, source, created_at, updated_at)
                VALUES (?, ?, 'BRL 과제', '2026-09-05', '2026-09-26', TRUE, 'AUTO_GENERATED',
                        '2026-09-01 00:00:00', '2026-09-01 00:00:00')
                """, categoryId, projectId);
        return String.valueOf(jdbc.queryForObject(
                "SELECT id FROM event WHERE research_project_id = ?", Long.class, projectId));
    }

}
