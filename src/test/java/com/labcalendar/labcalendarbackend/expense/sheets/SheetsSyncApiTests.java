package com.labcalendar.labcalendarbackend.expense.sheets;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import com.labcalendar.labcalendarbackend.auth.AuthCookie;
import com.labcalendar.labcalendarbackend.auth.AuthTier;
import com.labcalendar.labcalendarbackend.auth.token.AuthTokenCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The manual trigger, and what happens when the integration is off (KAN-88). */
@SpringBootTest
@AutoConfigureMockMvc
class SheetsSyncApiTests {

    @Autowired MockMvc mvc;
    @Autowired AuthTokenCodec tokens;

    private Cookie as(AuthTier tier) {
        return new Cookie(AuthCookie.NAME, tokens.issue(tier).value());
    }

    @Test
    void theViewerTierCannotStartASync() throws Exception {
        // 카드 지출을 쓰는 동작이다 — 조회 등급에는 지출 자체가 내려가지 않는다 (KAN-21)
        mvc.perform(post("/api/card-expenses/sheets-sync").cookie(as(AuthTier.VIEWER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void needsASession() throws Exception {
        mvc.perform(post("/api/card-expenses/sheets-sync"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void saysItIsSwitchedOffRatherThanPretendingTheRouteIsMissing() throws Exception {
        /*
         * 테스트 설정에서는 시트 연동이 꺼져 있다. 404 로 답하면 배포가 깨진 것처럼 읽히므로,
         * 켜지지 않았다는 사실 자체를 돌려준다.
         */
        mvc.perform(post("/api/card-expenses/sheets-sync").cookie(as(AuthTier.EDITOR)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SHEETS_SYNC_DISABLED"));
    }

    @Test
    void switchedOnWithoutASpreadsheetRefusesToStart() {
        var properties = new SheetsProperties();
        properties.setEnabled(true);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spreadsheet-id");

        properties.setSpreadsheetId("sheet-1");
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("credentials-json");
    }

    @Test
    void switchedOffValidatesNothingBecauseNothingWillRun() {
        var properties = new SheetsProperties();

        properties.validate();

        assertThat(properties.isEnabled()).isFalse();
    }

    @Test
    void refusesSettingsThatWouldTurnTheBrakesOff() {
        var properties = new SheetsProperties();
        properties.setEnabled(true);
        properties.setSpreadsheetId("sheet-1");
        properties.setCredentialsJson("{}");
        properties.setMaxRemovalRatio(2);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-removal-ratio");
    }
}
