package com.labcalendar.labcalendarbackend.expense.importing;

import java.io.ByteArrayOutputStream;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import jakarta.servlet.http.Cookie;
import tools.jackson.databind.json.JsonMapper;
import com.labcalendar.labcalendarbackend.auth.*;
import com.labcalendar.labcalendarbackend.auth.token.AuthTokenCodec;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=${MIGRATION_TEST_URL:jdbc:h2:mem:kan59;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}",
        "spring.servlet.multipart.max-file-size=5MB", "spring.servlet.multipart.max-request-size=6MB",
        "spring.servlet.multipart.file-size-threshold=6MB"})
@AutoConfigureMockMvc
class CardImportApiTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthTokenCodec auth;
    @Autowired LedgerSyncService sync;
    @LocalServerPort int port;
    private static final String URL = "/api/card-expenses/imports";

    @Test
    void verificationRunsInsideWriteTransactionAndRejectionLeavesNoHistory() {
        var months = List.of(new LedgerRowParser.ParsedMonth("2026년 9월", YearMonth.of(2026, 9), List.of(), List.of()));
        assertThatThrownBy(() -> sync.applyVerified("sample.xlsx", months, fingerprint -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                    .isEqualTo(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
            assertThat(fingerprint).isNotBlank();
            throw new ImportApiException(409, "PREVIEW_STALE");
        })).isInstanceOf(ImportApiException.class);
        assertThat(count("card_expense")).isZero();
        assertThat(count("sync_log")).isZero();
    }

    @BeforeEach @AfterEach
    void clean() {
        for (String table : List.of("sync_log_error", "sync_log", "event_participant", "event", "card_expense")) jdbc.update("DELETE FROM " + table);
    }
    @Test
    void defaultPreviewWritesNothingAndApplyCreatesCalendarRows() throws Exception {
        byte[] file = workbook("A", false, false);
        var response = request(file, null, null, AuthTier.EDITOR).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dryRun").value(true))
                .andExpect(jsonPath("$.data.totals.added").value(1));
        String token = token(response);
        assertThat(token).doesNotContain("홍길동", "sample.xlsx");
        assertThat(count("card_expense")).isZero();
        assertThat(count("sync_log")).isZero();
        request(file, "false", token, AuthTier.EDITOR).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dryRun").value(false))
                .andExpect(jsonPath("$.data.previewToken").isEmpty());
        assertThat(count("event")).isEqualTo(1);
        assertThat(count("event_participant")).isEqualTo(1);
        request(file, "false", token, AuthTier.EDITOR).andExpect(status().isConflict());
        String next = token(request(file, "true", null, AuthTier.EDITOR));
        request(file, "false", next, AuthTier.EDITOR).andExpect(status().isOk());
        request(file, "false", next, AuthTier.EDITOR).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totals.unchanged").value(1));
        assertThat(count("event")).isEqualTo(1);
    }

    @Test
    void rejectsMissingAndForgedTokensAndChangedFileWithoutWriting() throws Exception {
        byte[] file = workbook("A", false, false);
        String token = token(request(file, "true", null, AuthTier.EDITOR));
        request(file, "false", null, AuthTier.EDITOR).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PREVIEW_TOKEN_REQUIRED"))
                .andExpect(jsonPath("$.message").value("먼저 미리보기를 확인한 뒤 반영해 주세요."));
        request(file, "false", token + "x", AuthTier.EDITOR).andExpect(status().isBadRequest());
        request(workbook("B", false, false), "false", token, AuthTier.EDITOR).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PREVIEW_STALE"));
        assertThat(count("card_expense")).isZero();
        assertThat(count("sync_log")).isZero();
    }

    @Test
    void anotherUploadInvalidatesOldPreviewAndPreservesWinner() throws Exception {
        byte[] a = workbook("A", false, false), b = workbook("B", false, false);
        String old = token(request(a, "true", null, AuthTier.EDITOR));
        String fresh = token(request(b, "true", null, AuthTier.EDITOR));
        request(b, "false", fresh, AuthTier.EDITOR).andExpect(status().isOk());
        request(a, "false", old, AuthTier.EDITOR).andExpect(status().isConflict());
        assertThat(jdbc.queryForList("SELECT card_name FROM card_expense WHERE active=TRUE", String.class)).containsExactly("B");
    }

    @Test
    void fingerprintIncludesParticipantsAndInactiveExpenseContents() throws Exception {
        byte[] file = workbook("A", false, false);
        request(file, "false", token(request(file, "true", null, AuthTier.EDITOR)), AuthTier.EDITOR).andExpect(status().isOk());
        String participantPreview = token(request(file, "true", null, AuthTier.EDITOR));
        jdbc.update("UPDATE event_participant SET display_name='가상 변경'");
        request(file, "false", participantPreview, AuthTier.EDITOR).andExpect(status().isConflict());
        byte[] empty = workbook("", false, true);
        request(empty, "false", token(request(empty, "true", null, AuthTier.EDITOR)), AuthTier.EDITOR).andExpect(status().isOk());
        String inactivePreview = token(request(file, "true", null, AuthTier.EDITOR));
        jdbc.update("UPDATE card_expense SET purpose='changed' WHERE active=FALSE");
        request(file, "false", inactivePreview, AuthTier.EDITOR).andExpect(status().isConflict());
    }

    @Test
    void partialApplyBlocksInvalidMonthAndEmptyReadyRemovesOnlyAfterConfirmation() throws Exception {
        byte[] partial = workbook("A", true, false);
        var preview = request(partial, "true", null, AuthTier.EDITOR).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totals.blockedMonths").value(1))
                .andExpect(jsonPath("$.data.totals.skippedRows").value(1));
        request(partial, "false", token(preview), AuthTier.EDITOR).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM sync_log", String.class)).isEqualTo("PARTIAL");
        byte[] empty = workbook("", false, true);
        var deletion = request(empty, "true", null, AuthTier.EDITOR).andExpect(jsonPath("$.data.totals.removed").value(1));
        assertThat(count("event")).isEqualTo(1);
        request(empty, "false", token(deletion), AuthTier.EDITOR).andExpect(status().isOk());
        assertThat(count("event")).isZero();
    }

    @Test
    void allBlockedCanBePreviewedButNotApplied() throws Exception {
        byte[] file = workbook("", true, true);
        // Keep only the invalid month, removing the header-only READY month.
        try (var book = new XSSFWorkbook(new java.io.ByteArrayInputStream(file))) {
            book.removeSheetAt(0);
            var out = new ByteArrayOutputStream(); book.write(out); file = out.toByteArray();
        }
        String token = token(request(file, "true", null, AuthTier.EDITOR).andExpect(status().isOk()));
        request(file, "false", token, AuthTier.EDITOR).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_APPLICABLE_MONTHS"));
        assertThat(count("sync_log")).isZero();
    }

    @Test
    void bothModesRequireEditor() throws Exception {
        byte[] file = workbook("A", false, false);
        for (String mode : List.of("true", "false")) {
            request(file, mode, null, null).andExpect(status().isUnauthorized());
            request(file, mode, null, AuthTier.VIEWER).andExpect(status().isForbidden());
        }
    }

    @Test
    void badMultipartInputsReturnSafeErrors() throws Exception {
        request(new byte[0], "true", null, AuthTier.EDITOR).andExpect(status().isBadRequest());
        request("private-person".getBytes(), "true", null, AuthTier.EDITOR).andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-person"))));
        request(new byte[ExcelLedgerReader.MAX_FILE_BYTES + 1], "true", null, AuthTier.EDITOR)
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"))
                .andExpect(jsonPath("$.message").value("엑셀 파일은 5 MiB 이하로 업로드해 주세요."));
        request(workbook("A", false, false), "invalid", null, AuthTier.EDITOR).andExpect(status().isBadRequest());
        mvc.perform(multipart(URL).cookie(cookie(AuthTier.EDITOR))).andExpect(status().isBadRequest());
    }

    @Test
    void actualServletMultipartLimitRejectsOversizedFile() throws Exception {
        String boundary = "KAN59boundary";
        var bytes = new ByteArrayOutputStream();
        bytes.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"sample.xlsx\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        bytes.write(new byte[ExcelLedgerReader.MAX_FILE_BYTES + 1]);
        bytes.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var response = java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(
                java.net.URI.create("http://localhost:" + port + URL))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Cookie", AuthCookie.NAME + "=" + cookie(AuthTier.EDITOR).getValue())
                .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray())).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(413);
        var error = new tools.jackson.databind.json.JsonMapper().readTree(response.body());
        assertThat(error.get("code").asText()).isEqualTo("FILE_TOO_LARGE");
        assertThat(error.get("message").asText()).contains("5 MiB");
        assertThat(error.get("fieldErrors").isObject()).isTrue();
    }

    @Test
    void expiryBoundaryAndParserVersionAreVerified() throws Exception {
        var properties = new AuthProperties(); properties.setTokenSecret("test-only-secret-that-is-long-enough-32");
        Instant start = Instant.parse("2026-09-01T00:00:00Z");
        var codec = new PreviewTokenCodec(properties, Clock.fixed(start, ZoneOffset.UTC));
        String token = codec.issue("file", "state", "months");
        new PreviewTokenCodec(properties, Clock.fixed(start.plusSeconds(599), ZoneOffset.UTC)).verify(token, "file", "state", "months");
        assertThatThrownBy(() -> new PreviewTokenCodec(properties, Clock.fixed(start.plusSeconds(600), ZoneOffset.UTC))
                .verify(token, "file", "state", "months")).hasMessage("PREVIEW_STALE");
        String payload = token.substring(0, token.lastIndexOf('.')).replace("ledger-v1", "ledger-old");
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(properties.getTokenSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(mac.doFinal(("card-import-preview:" + payload).getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> codec.verify(payload + "." + signature, "file", "state", "months")).hasMessage("PREVIEW_STALE");
    }

    private ResultActions request(byte[] bytes, String mode, String token, AuthTier tier) throws Exception {
        var request = multipart(URL).file(new MockMultipartFile("file", "sample.xlsx", "application/octet-stream", bytes));
        if (mode != null) request.param("dryRun", mode);
        if (token != null) request.param("previewToken", token);
        if (tier != null) request.cookie(cookie(tier));
        return mvc.perform(request);
    }
    private Cookie cookie(AuthTier tier) { return new Cookie(AuthCookie.NAME, auth.issue(tier).value()); }
    private String token(ResultActions result) throws Exception {
        return JsonMapper.builder().build().readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data").get("previewToken").asText();
    }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private byte[] workbook(String card, boolean blocked, boolean empty) throws Exception {
        try (var book = new XSSFWorkbook()) {
            for (String name : blocked ? List.of("2026년 9월", "2026년 8월") : List.of("2026년 9월")) {
                var sheet = book.createSheet(name); var header = sheet.createRow(0);
                header.createCell(1).setCellValue("과제명"); header.createCell(2).setCellValue("인원"); header.createCell(3).setCellValue("점심");
                boolean invalid = name.contains("8월");
                if (!empty || invalid) {
                    var row = sheet.createRow(2); row.createCell(0).setCellValue(invalid ? 32 : 1);
                    row.createCell(1).setCellValue(card.isEmpty() ? "A" : card);
                    row.createCell(2).setCellValue("홍길동"); row.createCell(3).setCellValue("점심");
                }
            }
            var out = new ByteArrayOutputStream(); book.write(out); return out.toByteArray();
        }
    }
}
