package com.labcalendar.labcalendarbackend.expense.importing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import com.labcalendar.labcalendarbackend.auth.AuthCookie;
import com.labcalendar.labcalendarbackend.auth.AuthTier;
import com.labcalendar.labcalendarbackend.auth.token.AuthTokenCodec;
import jakarta.servlet.http.Cookie;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.datasource.url=${MIGRATION_TEST_URL:jdbc:h2:mem:kan60;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}")
@AutoConfigureMockMvc
class ImportHistoryTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthTokenCodec tokens;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM sync_log_error");
        jdbc.update("DELETE FROM sync_log");
    }

    Cookie editor() { return new Cookie(AuthCookie.NAME, tokens.issue(AuthTier.EDITOR).value()); }

    long insert(String status) {
        jdbc.update("""
                INSERT INTO sync_log (source_document_id, trigger_type, status, started_at, finished_at,
                    duration_ms, processed_count, created_count, deactivated_count, skipped_count, error_message)
                VALUES ('sample.xlsx','MANUAL',?,'2026-09-29 10:00:00','2026-09-29 10:00:01',1000,3,2,4,1,'private raw failure')
                """, status);
        return jdbc.queryForObject("SELECT MAX(id) FROM sync_log", Long.class);
    }

    @Test
    void requiresEditorEvenWhenHistoryIsEmpty() throws Exception {
        mvc.perform(get("/api/card-expenses/imports")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/card-expenses/imports").cookie(
                new Cookie(AuthCookie.NAME, tokens.issue(AuthTier.VIEWER).value())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/card-expenses/imports").cookie(editor()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    void returnsCountsStableNewestOrderAndSafeFailureWithoutWriting() throws Exception {
        insert("SUCCESS");
        long partial = insert("PARTIAL");
        long failed = insert("FAILED");
        jdbc.update("INSERT INTO sync_log_error (sync_log_id,source_locator,error_code,message) VALUES (?,?,?,?)",
                partial, "2026년 9월:8", "PARTICIPANTS_EMPTY", "WARNING");
        mvc.perform(get("/api/card-expenses/imports?limit=2").cookie(editor()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].id").value(Long.toString(failed)))
                .andExpect(jsonPath("$.data[0].errorCode").value("IMPORT_FAILED"))
                .andExpect(jsonPath("$.data[1].status").value("PARTIAL"))
                .andExpect(jsonPath("$.data[1].added").value(2))
                .andExpect(jsonPath("$.data[1].removed").value(4))
                .andExpect(jsonPath("$.data[1].skippedRows").value(1))
                .andExpect(jsonPath("$.data[1].problems[0].level").value("WARNING"))
                .andExpect(jsonPath("$.data[1].problems[0].locator").value("2026년 9월:8"))
                .andExpect(content().string(not(containsString("private raw failure"))));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sync_log", Integer.class)).isEqualTo(3);
    }

    @Test
    void boundsProblemListsAndDoesNotReturnRawDiagnosticMessages() throws Exception {
        long id = insert("PARTIAL");
        for (int i = 1; i <= 105; i++) {
            jdbc.update("INSERT INTO sync_log_error (sync_log_id,source_locator,error_code,message) VALUES (?,?,?,?)",
                    id, "2026년 9월:" + i, "CELL_ERROR", "private cell content");
        }
        mvc.perform(get("/api/card-expenses/imports").cookie(editor()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].problemCount").value(105))
                .andExpect(jsonPath("$.data[0].problems", hasSize(100)))
                .andExpect(jsonPath("$.data[0].problems[99].locator").value("2026년 9월:100"))
                .andExpect(content().string(not(containsString("private cell content"))));
    }

    @Test
    void defaultsToTwentyAndRejectsInvalidLimits() throws Exception {
        for (int i = 0; i < 21; i++) insert("SUCCESS");
        mvc.perform(get("/api/card-expenses/imports").cookie(editor()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(20)));
        for (String limit : new String[]{"0", "-1", "101", "abc", "999999999999999"}) {
            mvc.perform(get("/api/card-expenses/imports").param("limit", limit).cookie(editor()))
                    .andExpect(status().isBadRequest());
        }
    }
}
