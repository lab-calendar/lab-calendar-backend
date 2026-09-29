package com.labcalendar.labcalendarbackend;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import com.labcalendar.labcalendarbackend.auth.AuthCookie;
import com.labcalendar.labcalendarbackend.auth.AuthTier;
import com.labcalendar.labcalendarbackend.auth.token.AuthTokenCodec;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may call what, and what each tier is allowed to see (KAN-35, KAN-21).
 *
 * <p>Tokens are minted directly rather than by signing in: this suite is about what a tier may do
 * once it holds one, and the password check has its own tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthorizationApiTests {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthTokenCodec tokens;

    private Cookie as(AuthTier tier) {
        return new Cookie(AuthCookie.NAME, tokens.issue(tier).value());
    }

    private static final String NEW_EVENT = """
            {"title":"새 일정","startDate":"2026-09-10","endDate":"2026-09-10",
             "categoryKey":"lab","participants":[]}
            """;

    // ── 인증 ────────────────────────────────────────────────

    @Test
    void theApiIsClosedWithoutASession() throws Exception {
        for (String path : new String[]{"/api/events?from=2026-09-01&to=2026-09-30",
                "/api/events/1", "/api/projects", "/api/members"}) {
            mvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
    }

    @Test
    void writesAreClosedWithoutASessionToo() throws Exception {
        mvc.perform(post("/api/events").contentType("application/json").content(NEW_EVENT))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aGarbledCookieIsTreatedAsNoSession() throws Exception {
        mvc.perform(get("/api/projects").cookie(new Cookie(AuthCookie.NAME, "not-a-token")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theHealthProbeAndSignInStayOpen() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk());
        // 로그인할 수 없으면 아무도 세션을 얻지 못한다.
        mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("""
                                {"password":"whatever"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authenticated").value(false));
    }

    // ── 등급별 쓰기 권한 ─────────────────────────────────────

    @Test
    void viewersMayRead() throws Exception {
        mvc.perform(get("/api/events?from=2026-09-01&to=2026-09-30").cookie(as(AuthTier.VIEWER)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/projects").cookie(as(AuthTier.VIEWER)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/members").cookie(as(AuthTier.VIEWER)))
                .andExpect(status().isOk());
    }

    @Test
    void viewersMayNotWriteEvents() throws Exception {
        Cookie viewer = as(AuthTier.VIEWER);

        mvc.perform(post("/api/events").contentType("application/json").content(NEW_EVENT)
                        .cookie(viewer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(put("/api/events/1").contentType("application/json").content(NEW_EVENT)
                        .cookie(viewer))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/events/1").cookie(viewer))
                .andExpect(status().isForbidden());
    }

    @Test
    void viewersMayNotWriteProjectsOrMembers() throws Exception {
        Cookie viewer = as(AuthTier.VIEWER);

        mvc.perform(post("/api/projects").contentType("application/json").content("""
                        {"name":"과제","endDate":"2026-12-31","leadTimeDays":21,"active":true}
                        """).cookie(viewer))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/members").contentType("application/json").content("""
                        {"name":"홍길동","active":true}
                        """).cookie(viewer))
                .andExpect(status().isForbidden());
    }

    @Test
    void theRefusalComesBeforeTheRequestIsEvenValid() throws Exception {
        // 검증 오류(400)가 아니라 권한 거부(403)가 먼저다 — 본문을 들여다보지 않는다.
        mvc.perform(post("/api/events").contentType("application/json")
                        .content("""
                                {"title":""}
                                """)
                        .cookie(as(AuthTier.VIEWER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void editorsMayWrite() throws Exception {
        mvc.perform(post("/api/events").contentType("application/json").content(NEW_EVENT)
                        .cookie(as(AuthTier.EDITOR)))
                .andExpect(status().isCreated());
    }

    // ── 조회 등급의 카드 데이터 차단 ──────────────────────────

    @Test
    void viewersDoNotReceiveSyncedCardEvents() throws Exception {
        insertSyncedCardEvent("[법인카드 A]");

        mvc.perform(get("/api/events?from=2026-09-01&to=2026-09-30").cookie(as(AuthTier.VIEWER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.categoryKey == 'card')]").isEmpty());
    }

    @Test
    void editorsDoReceiveThem() throws Exception {
        insertSyncedCardEvent("[법인카드 A]");

        mvc.perform(get("/api/events?from=2026-09-01&to=2026-09-30").cookie(as(AuthTier.EDITOR)))
                .andExpect(jsonPath("$.data[?(@.categoryKey == 'card')]").isNotEmpty());
    }

    @Test
    void askingForTheCardCategoryDirectlyGivesAViewerNothing() throws Exception {
        insertSyncedCardEvent("[법인카드 A]");

        // 필터를 카드로 찍어도 우회가 되지 않는다.
        mvc.perform(get("/api/events?from=2026-09-01&to=2026-09-30&categories=card")
                        .cookie(as(AuthTier.VIEWER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void aManualEventFiledUnderCardIsWithheldToo() throws Exception {
        // 동기화된 것이 아니어도 카드 카테고리면 지출 정보다.
        Long categoryId = jdbc.queryForObject(
                "SELECT id FROM category WHERE code = 'card'", Long.class);
        jdbc.update("""
                INSERT INTO event (category_id, title, start_date, end_date, all_day, source,
                        created_at, updated_at)
                VALUES (?, '손으로 적은 카드 사용', '2026-09-12', '2026-09-12', TRUE, 'MANUAL',
                        '2026-09-01 00:00:00', '2026-09-01 00:00:00')
                """, categoryId);

        mvc.perform(get("/api/events?from=2026-09-01&to=2026-09-30").cookie(as(AuthTier.VIEWER)))
                .andExpect(jsonPath("$.data[?(@.title == '손으로 적은 카드 사용')]").isEmpty());
    }

    @Test
    void aViewerReadingACardEventByIdIsToldItDoesNotExist() throws Exception {
        Long eventId = insertSyncedCardEvent("[연구비카드 B]");

        // 403 이면 그 id 에 지출이 있다는 사실을 알려주는 셈이다.
        mvc.perform(get("/api/events/" + eventId).cookie(as(AuthTier.VIEWER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        mvc.perform(get("/api/events/" + eventId).cookie(as(AuthTier.EDITOR)))
                .andExpect(status().isOk());
    }

    @Test
    void ordinaryEventsAreUntouchedForViewers() throws Exception {
        Long categoryId = jdbc.queryForObject(
                "SELECT id FROM category WHERE code = 'lab'", Long.class);
        jdbc.update("""
                INSERT INTO event (category_id, title, start_date, end_date, all_day, source,
                        created_at, updated_at)
                VALUES (?, '정기 주간 랩미팅', '2026-09-10', '2026-09-10', TRUE, 'MANUAL',
                        '2026-09-01 00:00:00', '2026-09-01 00:00:00')
                """, categoryId);

        mvc.perform(get("/api/events?from=2026-09-01&to=2026-09-30").cookie(as(AuthTier.VIEWER)))
                .andExpect(jsonPath("$.data[?(@.title == '정기 주간 랩미팅')]").isNotEmpty());
    }

    /** A card entry as the Google sync would leave it (KAN-58). */
    private Long insertSyncedCardEvent(String cardName) {
        jdbc.update("""
                INSERT INTO card_expense (source_document_id, source_record_id, used_on, card_name,
                        purpose, usage_type, active, last_seen_at, created_at, updated_at)
                VALUES ('doc-1', ?, '2026-09-11', ?, '다과비', '회의', TRUE,
                        '2026-09-01 00:00:00', '2026-09-01 00:00:00', '2026-09-01 00:00:00')
                """, cardName, cardName);
        Long expenseId = jdbc.queryForObject(
                "SELECT id FROM card_expense WHERE source_record_id = ?", Long.class, cardName);
        Long categoryId = jdbc.queryForObject(
                "SELECT id FROM category WHERE code = 'card'", Long.class);

        jdbc.update("""
                INSERT INTO event (category_id, card_expense_id, title, start_date, end_date,
                        all_day, source, created_at, updated_at)
                VALUES (?, ?, ?, '2026-09-11', '2026-09-11', TRUE, 'GOOGLE_SYNC',
                        '2026-09-01 00:00:00', '2026-09-01 00:00:00')
                """, categoryId, expenseId, cardName);
        return jdbc.queryForObject(
                "SELECT id FROM event WHERE card_expense_id = ?", Long.class, expenseId);
    }
}
