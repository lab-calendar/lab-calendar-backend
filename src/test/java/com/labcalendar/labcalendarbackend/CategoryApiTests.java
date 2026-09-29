package com.labcalendar.labcalendarbackend;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import com.labcalendar.labcalendarbackend.auth.AuthCookie;
import com.labcalendar.labcalendarbackend.auth.AuthTier;
import com.labcalendar.labcalendarbackend.auth.token.AuthTokenCodec;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Category listing (KAN-38, docs/api-contract.md §5).
 *
 * <p>The three rows come from the seed migration, so these run against what the app really ships
 * with rather than fixtures written here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CategoryApiTests {

    @Autowired MockMvc mvc;
    @Autowired AuthTokenCodec tokens;

    private Cookie as(AuthTier tier) {
        return new Cookie(AuthCookie.NAME, tokens.issue(tier).value());
    }

    @Test
    void listsTheThreeCategoriesInTheOrderTheLabReadsThem() throws Exception {
        mvc.perform(get("/api/categories").cookie(as(AuthTier.EDITOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data[*].key", contains("project", "lab", "card")))
                .andExpect(jsonPath("$.data[*].name", contains(
                        "과제/연구 관리", "랩실 주기적 일정", "카드/경비 사용")));
    }

    @Test
    void doesNotSendColours() throws Exception {
        // 색상은 프론트 디자인 토큰이 관리한다. 여기서 내려주면 DB 가 대비 기준을 조용히 무너뜨린다.
        mvc.perform(get("/api/categories").cookie(as(AuthTier.EDITOR)))
                .andExpect(jsonPath("$.data[0].color").doesNotExist())
                .andExpect(jsonPath("$.data[0].colour").doesNotExist());
    }

    @Test
    void idIsAStringLikeEverywhereElse() throws Exception {
        mvc.perform(get("/api/categories").cookie(as(AuthTier.EDITOR)))
                .andExpect(jsonPath("$.data[0].id").isString());
    }

    @Test
    void theViewerTierDoesNotGetTheCardCategory() throws Exception {
        /*
         * 빈 "카드/경비 사용" 체크박스는 그 자체로 답이 된다 — 데이터가 있고 당신에게만
         * 가려져 있다는 뜻이다. 그 질문을 아예 못 하게 하는 것이 이 등급의 목적이다 (KAN-21).
         */
        mvc.perform(get("/api/categories").cookie(as(AuthTier.VIEWER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[*].key", contains("project", "lab")));
    }

    @Test
    void needsASession() throws Exception {
        mvc.perform(get("/api/categories"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
