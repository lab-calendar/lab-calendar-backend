package com.labcalendar.labcalendarbackend;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import com.labcalendar.labcalendarbackend.config.TimeConfig;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Research project CRUD against the real schema (KAN-47).
 *
 * <p>Rolled back per test so the migration suite still sees empty tables. Dates are built from the
 * service time zone so the D-Day expectations hold wherever CI runs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProjectApiTests {

    @Autowired MockMvc mvc;

    private static LocalDate today() {
        return LocalDate.now(TimeConfig.SERVICE_ZONE);
    }

    private static String body(String name, LocalDate endDate, int leadTimeDays) {
        return """
                {"name":"%s","submissionStage":"연차보고서","endDate":"%s",
                 "leadTimeDays":%d,"active":true}
                """.formatted(name, endDate, leadTimeDays);
    }

    @Test
    void createsProjectAndComputesItsSchedule() throws Exception {
        LocalDate deadline = today().plusDays(10);

        mvc.perform(post("/api/projects").contentType("application/json")
                        .content(body("BRL 과제", deadline, 21)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").exists())
                .andExpect(jsonPath("$.data.name").value("BRL 과제"))
                .andExpect(jsonPath("$.data.submissionStage").value("연차보고서"))
                .andExpect(jsonPath("$.data.endDate").value(deadline.toString()))
                .andExpect(jsonPath("$.data.leadTimeDays").value(21))
                .andExpect(jsonPath("$.data.active").value(true))
                .andExpect(jsonPath("$.data.dDay").value(10))
                .andExpect(jsonPath("$.data.preparationStartDate")
                        .value(deadline.minusDays(21).toString()));
    }

    @Test
    void submissionStageIsOptional() throws Exception {
        mvc.perform(post("/api/projects").contentType("application/json").content("""
                        {"name":"학회 신청","endDate":"%s","leadTimeDays":7,"active":true}
                        """.formatted(today().plusDays(30))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.submissionStage").doesNotExist());
    }

    @Test
    void blankSubmissionStageIsStoredAsAbsent() throws Exception {
        mvc.perform(post("/api/projects").contentType("application/json").content("""
                        {"name":"학회 신청","submissionStage":"   ","endDate":"%s",
                         "leadTimeDays":7,"active":true}
                        """.formatted(today().plusDays(30))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.submissionStage").doesNotExist());
    }

    @Test
    void deadlineTodayIsDDayZeroAndAPastDeadlineIsNegative() throws Exception {
        String todayId = create(body("오늘 마감", today(), 0));
        String pastId = create(body("지난 과제", today().minusDays(5), 14));

        mvc.perform(get("/api/projects/" + todayId))
                .andExpect(jsonPath("$.data.dDay").value(0))
                .andExpect(jsonPath("$.data.preparationStartDate").value(today().toString()));
        mvc.perform(get("/api/projects/" + pastId))
                .andExpect(jsonPath("$.data.dDay").value(-5));
    }

    @Test
    void listsActiveProjectsFirstThenBySoonestDeadline() throws Exception {
        create(body("나중 마감", today().plusDays(60), 21));
        create(body("곧 마감", today().plusDays(3), 21));
        String hidden = create(body("숨긴 과제", today().plusDays(1), 21));
        deactivate(hidden, "숨긴 과제", today().plusDays(1));

        mvc.perform(get("/api/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data[*].name", contains("곧 마감", "나중 마감", "숨긴 과제")));
    }

    @Test
    void updatesEveryEditableFieldAndRecomputesTheSchedule() throws Exception {
        String id = create(body("BRL 과제", today().plusDays(10), 21));
        LocalDate moved = today().plusDays(40);

        mvc.perform(put("/api/projects/" + id).contentType("application/json").content("""
                        {"name":"BRL 과제 2단계","submissionStage":"최종보고서","endDate":"%s",
                         "leadTimeDays":14,"active":false}
                        """.formatted(moved)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("BRL 과제 2단계"))
                .andExpect(jsonPath("$.data.submissionStage").value("최종보고서"))
                .andExpect(jsonPath("$.data.leadTimeDays").value(14))
                .andExpect(jsonPath("$.data.active").value(false))
                .andExpect(jsonPath("$.data.dDay").value(40))
                .andExpect(jsonPath("$.data.preparationStartDate")
                        .value(moved.minusDays(14).toString()));
    }

    @Test
    void deletesProject() throws Exception {
        String id = create(body("지울 과제", today().plusDays(10), 21));

        mvc.perform(delete("/api/projects/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/projects/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void missingProjectIsNotFound() throws Exception {
        mvc.perform(get("/api/projects/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void rejectsBlankName() throws Exception {
        mvc.perform(post("/api/projects").contentType("application/json")
                        .content(body("   ", today().plusDays(10), 21)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.name").exists());
    }

    @Test
    void rejectsMissingDeadline() throws Exception {
        mvc.perform(post("/api/projects").contentType("application/json").content("""
                        {"name":"마감 없음","leadTimeDays":21,"active":true}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.endDate").exists());
    }

    @Test
    void rejectsLeadTimeOutsideTheAgreedRange() throws Exception {
        mvc.perform(post("/api/projects").contentType("application/json")
                        .content(body("음수", today().plusDays(10), -1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.leadTimeDays").exists());

        mvc.perform(post("/api/projects").contentType("application/json")
                        .content(body("너무 김", today().plusDays(10), 183)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.leadTimeDays").exists());
    }

    @Test
    void acceptsTheEndsOfTheAgreedLeadTimeRange() throws Exception {
        mvc.perform(post("/api/projects").contentType("application/json")
                        .content(body("하한", today().plusDays(10), 0)))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/projects").contentType("application/json")
                        .content(body("상한", today().plusDays(200), 182)))
                .andExpect(status().isCreated());
    }

    @Test
    void keepsALeadTimeThatIsNotAWholeNumberOfWeeks() throws Exception {
        LocalDate deadline = today().plusDays(30);

        mvc.perform(post("/api/projects").contentType("application/json")
                        .content(body("열흘 준비", deadline, 10)))
                .andExpect(jsonPath("$.data.leadTimeDays").value(10))
                .andExpect(jsonPath("$.data.preparationStartDate")
                        .value(deadline.minusDays(10).toString()));
    }

    private String create(String body) throws Exception {
        String response = mvc.perform(post("/api/projects").contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return response.replaceAll(".*\"id\"\\s*:\\s*\"(\\d+)\".*", "$1");
    }

    /**
     * Hidden through the API rather than a direct UPDATE: the project is already managed in this
     * transaction, so a raw write would not be reflected in what the next query returns.
     */
    private void deactivate(String id, String name, LocalDate endDate) throws Exception {
        mvc.perform(put("/api/projects/" + id).contentType("application/json").content("""
                        {"name":"%s","endDate":"%s","leadTimeDays":21,"active":false}
                        """.formatted(name, endDate)))
                .andExpect(status().isOk());
    }
}
