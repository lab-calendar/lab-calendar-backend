package com.labcalendar.labcalendarbackend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import com.labcalendar.labcalendarbackend.support.EditorSession;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lab roster management against the real schema (KAN-41).
 *
 * <p>Rolled back per test so the migration suite still sees empty tables.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(EditorSession.class)
@Transactional
class MemberApiTests {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private static String body(String name, boolean active) {
        return """
                {"name":"%s","active":%b}
                """.formatted(name, active);
    }

    @Test
    void registersMember() throws Exception {
        mvc.perform(post("/api/members").contentType("application/json").content(body("홍길동", true)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").exists())
                .andExpect(jsonPath("$.data.name").value("홍길동"))
                .andExpect(jsonPath("$.data.active").value(true));
    }

    @Test
    void allowsTwoMembersWithTheSameName() throws Exception {
        String first = create(body("홍길동", true));
        String second = create(body("홍길동", true));

        // 동명이인은 이름이 아니라 id 로 구분된다
        org.assertj.core.api.Assertions.assertThat(first).isNotEqualTo(second);
        mvc.perform(get("/api/members"))
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[*].name", contains("홍길동", "홍길동")));
    }

    @Test
    void trimsSurroundingWhitespaceFromTheName() throws Exception {
        mvc.perform(post("/api/members").contentType("application/json")
                        .content(body("  김철수  ", true)))
                .andExpect(jsonPath("$.data.name").value("김철수"));
    }

    @Test
    void listsCurrentMembersFirstThenByName() throws Exception {
        create(body("최영희", true));
        create(body("김철수", true));
        String left = create(body("강길동", true));
        mvc.perform(put("/api/members/" + left).contentType("application/json")
                .content(body("강길동", false))).andExpect(status().isOk());

        mvc.perform(get("/api/members"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data[*].name", contains("김철수", "최영희", "강길동")));
    }

    @Test
    void listKeepsFormerMembersWithTheirFlag() throws Exception {
        String id = create(body("퇴실한 연구원", true));
        mvc.perform(put("/api/members/" + id).contentType("application/json")
                .content(body("퇴실한 연구원", false))).andExpect(status().isOk());

        mvc.perform(get("/api/members"))
                .andExpect(jsonPath("$.data[0].name").value("퇴실한 연구원"))
                .andExpect(jsonPath("$.data[0].active").value(false));
    }

    @Test
    void renamesAndReactivates() throws Exception {
        String id = create(body("이영희", false));

        mvc.perform(put("/api/members/" + id).contentType("application/json")
                        .content(body("이영희(복귀)", true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("이영희(복귀)"))
                .andExpect(jsonPath("$.data.active").value(true));
    }

    @Test
    void deletesMemberWhoWasNeverReferenced() throws Exception {
        String id = create(body("오등록", true));

        mvc.perform(delete("/api/members/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/members/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void refusesToDeleteAMemberWhoRanAnEvent() throws Exception {
        String id = create(body("담당 연구원", true));
        insertEventOwnedBy(Long.valueOf(id));

        mvc.perform(delete("/api/members/" + id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void refusesToDeleteAMemberWhoAttendedAnEvent() throws Exception {
        String id = create(body("참석 연구원", true));
        Long eventId = insertEventOwnedBy(null);
        jdbc.update("""
                INSERT INTO event_participant (event_id, member_id, display_name, position,
                        created_at, updated_at)
                VALUES (?, ?, '참석 연구원', 0, '2026-09-01 00:00:00', '2026-09-01 00:00:00')
                """, eventId, Long.valueOf(id));

        mvc.perform(delete("/api/members/" + id)).andExpect(status().isConflict());
    }

    @Test
    void missingMemberIsNotFound() throws Exception {
        mvc.perform(get("/api/members/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void rejectsBlankName() throws Exception {
        mvc.perform(post("/api/members").contentType("application/json").content(body("   ", true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.name").exists());
    }

    @Test
    void rejectsMissingActiveFlag() throws Exception {
        mvc.perform(post("/api/members").contentType("application/json").content("""
                        {"name":"상태 없음"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.active").exists());
    }

    private String create(String body) throws Exception {
        String response = mvc.perform(post("/api/members").contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return response.replaceAll(".*\"id\"\\s*:\\s*\"(\\d+)\".*", "$1");
    }

    /** Written straight to the tables: the event API is a separate ticket (KAN-39). */
    private Long insertEventOwnedBy(Long ownerMemberId) {
        Long categoryId = jdbc.queryForObject(
                "SELECT id FROM category WHERE code = 'lab'", Long.class);
        jdbc.update("""
                INSERT INTO event (category_id, owner_member_id, title, start_date, end_date,
                        all_day, source, created_at, updated_at)
                VALUES (?, ?, '정기 주간 랩미팅', '2026-09-10', '2026-09-10', TRUE, 'MANUAL',
                        '2026-09-01 00:00:00', '2026-09-01 00:00:00')
                """, categoryId, ownerMemberId);
        return jdbc.queryForObject(
                "SELECT id FROM event WHERE title = '정기 주간 랩미팅'", Long.class);
    }
}
