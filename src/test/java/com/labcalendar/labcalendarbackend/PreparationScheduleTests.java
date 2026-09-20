package com.labcalendar.labcalendarbackend;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import com.labcalendar.labcalendarbackend.config.TimeConfig;
import com.labcalendar.labcalendarbackend.project.service.PreparationScheduleService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Preparation schedules generated from projects (KAN-49).
 *
 * <p>기획서 3.1 — registering a project is supposed to be enough to see its preparation period on
 * the calendar as a bar. These check that it appears, that it follows the deadline when that
 * moves, and above all that running the batch again does not pile up duplicates.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PreparationScheduleTests {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PreparationScheduleService schedules;
    @PersistenceContext EntityManager entityManager;

    private static LocalDate today() {
        return LocalDate.now(TimeConfig.SERVICE_ZONE);
    }

    private static String body(String name, LocalDate endDate, int leadTimeDays, boolean active) {
        return """
                {"name":"%s","submissionStage":"연차보고서","endDate":"%s",
                 "leadTimeDays":%d,"active":%b}
                """.formatted(name, endDate, leadTimeDays, active);
    }

    // ── 생성 ────────────────────────────────────────────────

    @Test
    void registeringAProjectPutsItsPreparationPeriodOnTheCalendar() throws Exception {
        LocalDate deadline = today().plusDays(10);
        create(body("BRL 과제", deadline, 21, true));

        // 등록만으로 나타나야 한다 — 배치를 기다리지 않는다
        mvc.perform(get(range(deadline)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].source").value("AUTO_GENERATED"))
                .andExpect(jsonPath("$.data[0].title").value("BRL 과제"))
                .andExpect(jsonPath("$.data[0].detail").value("연차보고서"))
                .andExpect(jsonPath("$.data[0].categoryKey").value("project"))
                .andExpect(jsonPath("$.data[0].startDate").value(deadline.minusDays(21).toString()))
                .andExpect(jsonPath("$.data[0].endDate").value(deadline.toString()));
    }

    @Test
    void theStoredTitleSaysWhatTheRowIsEvenThoughTheCalendarShowsTheProjectName() throws Exception {
        create(body("BRL 과제", today().plusDays(10), 21, true));

        // 화면은 과제명을 쓰지만(계약 §6.2), 테이블을 직접 열어 본 사람도 알아볼 수 있어야 한다
        assertThat(storedTitle()).isEqualTo("[작성 요망] BRL 과제 연차보고서 준비 시작");
    }

    @Test
    void aLeadTimeOfZeroLeavesASingleDayOnTheDeadline() throws Exception {
        LocalDate deadline = today().plusDays(5);
        create(body("당일 제출", deadline, 0, true));

        assertThat(startDate()).isEqualTo(deadline);
    }

    // ── 멱등성 ───────────────────────────────────────────────

    @Test
    void runningTheBatchAgainChangesNothing() throws Exception {
        create(body("BRL 과제", today().plusDays(10), 21, true));

        PreparationScheduleService.SyncResult first = schedules.syncAll();
        PreparationScheduleService.SyncResult second = schedules.syncAll();

        // 등록에서 이미 만들었으므로 배치는 할 일이 없다
        assertThat(first.created()).isZero();
        assertThat(first.moved()).isZero();
        assertThat(second.created()).isZero();
        assertThat(generatedCount()).isEqualTo(1);
    }

    @Test
    void theBatchPicksUpAProjectWrittenStraightToTheTable() {
        // 앱을 거치지 않고 들어온 행도 다음 배치가 주워 간다
        insertProjectDirectly("손으로 넣은 과제", today().plusDays(30), 14);

        assertThat(schedules.syncAll().created()).isEqualTo(1);
        assertThat(generatedCount()).isEqualTo(1);
        // 두 번째에는 아무것도 하지 않는다
        assertThat(schedules.syncAll().created()).isZero();
        assertThat(generatedCount()).isEqualTo(1);
    }

    // ── 변경 추적 ─────────────────────────────────────────────

    @Test
    void movingTheDeadlineMovesTheSchedule() throws Exception {
        String id = create(body("BRL 과제", today().plusDays(10), 21, true));
        LocalDate moved = today().plusDays(40);

        mvc.perform(put("/api/projects/" + id).contentType("application/json")
                        .content(body("BRL 과제", moved, 21, true)))
                .andExpect(status().isOk());

        mvc.perform(get(range(moved)))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].startDate").value(moved.minusDays(21).toString()))
                .andExpect(jsonPath("$.data[0].endDate").value(moved.toString()));
        assertThat(generatedCount()).isEqualTo(1);
    }

    @Test
    void shorteningTheLeadTimeShrinksThePeriod() throws Exception {
        LocalDate deadline = today().plusDays(30);
        String id = create(body("BRL 과제", deadline, 21, true));

        mvc.perform(put("/api/projects/" + id).contentType("application/json")
                .content(body("BRL 과제", deadline, 7, true))).andExpect(status().isOk());

        // API 로 읽는다 — 원시 SQL 은 아직 플러시되지 않은 수정을 보지 못해 예전 값을 돌려준다
        mvc.perform(get(range(deadline)))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].startDate").value(deadline.minusDays(7).toString()));
    }

    @Test
    void renamingTheProjectIsReflectedWithoutTouchingTheEventRow() throws Exception {
        LocalDate deadline = today().plusDays(10);
        String id = create(body("옛 이름", deadline, 21, true));

        mvc.perform(put("/api/projects/" + id).contentType("application/json")
                .content(body("새 이름", deadline, 21, true))).andExpect(status().isOk());

        // 표시 이름은 과제 행에서 읽으므로 일정이 낡은 이름을 들고 있을 수 없다
        mvc.perform(get(range(deadline)))
                .andExpect(jsonPath("$.data[0].title").value("새 이름"));
    }

    // ── 숨김과 삭제 ───────────────────────────────────────────

    @Test
    void hidingAProjectTakesItsPeriodOffTheCalendarButKeepsTheRow() throws Exception {
        LocalDate deadline = today().plusDays(10);
        String id = create(body("숨길 과제", deadline, 21, true));

        mvc.perform(put("/api/projects/" + id).contentType("application/json")
                .content(body("숨길 과제", deadline, 21, false))).andExpect(status().isOk());

        // 계약 §7.3 — 조회가 걸러 내므로 행을 지웠다 다시 만들 필요가 없다
        mvc.perform(get(range(deadline)))
                .andExpect(jsonPath("$.data", hasSize(0)));
        assertThat(generatedCount()).isEqualTo(1);
    }

    @Test
    void showingItAgainBringsThePeriodBack() throws Exception {
        LocalDate deadline = today().plusDays(10);
        String id = create(body("숨겼다 켠 과제", deadline, 21, false));

        mvc.perform(put("/api/projects/" + id).contentType("application/json")
                .content(body("숨겼다 켠 과제", deadline, 21, true))).andExpect(status().isOk());

        mvc.perform(get(range(deadline))).andExpect(jsonPath("$.data", hasSize(1)));
    }

    @Test
    void deletingAProjectTakesItsScheduleWithIt() throws Exception {
        String id = create(body("지울 과제", today().plusDays(10), 21, true));

        // 이제 모든 과제가 일정을 갖는다. 스스로 치우지 않으면 삭제가 항상 409 가 된다.
        mvc.perform(delete("/api/projects/" + id)).andExpect(status().isNoContent());
        assertThat(generatedCount()).isZero();
    }

    // ── 수동 트리거 ───────────────────────────────────────────

    @Test
    void theManualTriggerReportsWhatItDid() throws Exception {
        insertProjectDirectly("손으로 넣은 과제", today().plusDays(30), 14);

        mvc.perform(post("/api/projects/schedule-sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created").value(1))
                .andExpect(jsonPath("$.data.moved").value(0))
                .andExpect(jsonPath("$.data.projects").value(1));
    }

    @Test
    void aHealthySystemReportsNothingToDo() throws Exception {
        create(body("BRL 과제", today().plusDays(10), 21, true));

        mvc.perform(post("/api/projects/schedule-sync"))
                .andExpect(jsonPath("$.data.created").value(0))
                .andExpect(jsonPath("$.data.moved").value(0));
    }

    @Test
    void theBatchMovesAScheduleThatDriftedOutOfStep() {
        insertProjectDirectly("손으로 넣은 과제", today().plusDays(30), 14);
        schedules.syncAll();

        /*
         * 원시 SQL 로 일정을 땅에 떨어뜨린다. 그전에 플러시해야 방금 만든 행이 테이블에
         * 있고, 뒤에 clear 해야 다음 배치가 캐시된 옛 엔티티 대신 바뀜 행을 읽는다.
         */
        entityManager.flush();
        jdbc.update("UPDATE event SET start_date = ?, end_date = ? WHERE source = 'AUTO_GENERATED'",
                LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 2));
        entityManager.clear();

        assertThat(schedules.syncAll().moved()).isEqualTo(1);
        entityManager.flush();
        assertThat(startDate()).isEqualTo(today().plusDays(30).minusDays(14));
    }

    // ── 도우미 ───────────────────────────────────────────────

    /** A window wide enough that the period falls inside it wherever the deadline sits. */
    private static String range(LocalDate around) {
        return "/api/events?from=" + around.minusDays(200) + "&to=" + around.plusDays(200);
    }

    private String create(String body) throws Exception {
        String response = mvc.perform(post("/api/projects").contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return response.replaceAll(".*\"id\"\\s*:\\s*\"(\\d+)\".*", "$1");
    }

    private int generatedCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM event WHERE source = 'AUTO_GENERATED'", Integer.class);
    }

    private LocalDate startDate() {
        return jdbc.queryForObject(
                "SELECT start_date FROM event WHERE source = 'AUTO_GENERATED'", LocalDate.class);
    }

    private String storedTitle() {
        return jdbc.queryForObject(
                "SELECT title FROM event WHERE source = 'AUTO_GENERATED'", String.class);
    }

    private void insertProjectDirectly(String name, LocalDate endDate, int leadTimeDays) {
        jdbc.update("""
                INSERT INTO research_project (name, submission_type, end_date, lead_time_days,
                        active, created_at, updated_at)
                VALUES (?, '연차보고서', ?, ?, TRUE, '2026-09-01 00:00:00', '2026-09-01 00:00:00')
                """, name, endDate, leadTimeDays);
    }
}
