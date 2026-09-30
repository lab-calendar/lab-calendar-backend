package com.labcalendar.labcalendarbackend.expense.sheets;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import com.labcalendar.labcalendarbackend.expense.importing.LedgerRowParser;
import com.labcalendar.labcalendarbackend.expense.importing.LedgerSheetSelector;
import com.labcalendar.labcalendarbackend.expense.importing.LedgerSyncService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scheduled sync, end to end but without Google (KAN-88, KAN-89).
 *
 * <p>Everything below the HTTP boundary is the real thing — selection, parsing, reconciliation and
 * the database — so these say what the lab would actually see in the calendar.
 */
@SpringBootTest(properties = "spring.datasource.url=${MIGRATION_TEST_URL:jdbc:h2:mem:kan88;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}")
class SheetsSyncServiceTests {

    /** 2026-09-30 in Seoul: the window covers September, August and July. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-30T01:00:00Z"), ZoneId.of("UTC"));
    private static final String SEPTEMBER = "2026년 9월";

    @Autowired LedgerSheetSelector selector;
    @Autowired LedgerRowParser parser;
    @Autowired LedgerSyncService sync;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM sync_log_error");
        jdbc.update("DELETE FROM sync_log");
        jdbc.update("DELETE FROM event_participant");
        jdbc.update("DELETE FROM event");
        jdbc.update("DELETE FROM card_expense");
    }

    private SheetsProperties properties() {
        var properties = new SheetsProperties();
        properties.setMonthsBack(3);
        properties.setMaxRemovalsPerMonth(20);
        properties.setMaxRemovalRatio(0.5);
        return properties;
    }

    private SheetsSyncService service(SheetsClient client, SheetsProperties properties) {
        return new SheetsSyncService(new SheetsLedgerReader(client, properties, CLOCK),
                selector, parser, sync, properties, jdbc, CLOCK);
    }

    private SheetsSyncService service(SheetsClient client) {
        return service(client, properties());
    }

    /** {@code count} ordinary lunch rows, one per day from the first of September. */
    private List<List<String>> lunches(int count) {
        var rows = new ArrayList<List<String>>();
        for (int day = 1; day <= count; day++) {
            rows.add(FakeSheetsClient.row(String.valueOf(day), "가상 과제 " + day, "홍길동, 김철수", "점심"));
        }
        return rows;
    }

    private long activeRows() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM card_expense WHERE source_document_id='ledger:2026-09' AND active=TRUE",
                Long.class);
    }

    private String lastLog(String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM sync_log ORDER BY id DESC LIMIT 1", String.class);
    }

    @Test
    void writesWhatTheSheetSaysIntoTheCalendar() {
        var outcome = service(new FakeSheetsClient().month(SEPTEMBER, lunches(3)))
                .run(LedgerSyncService.SCHEDULED);

        assertThat(outcome.applied()).isTrue();
        assertThat(outcome.added()).isEqualTo(3);
        assertThat(activeRows()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event", Long.class)).isEqualTo(3);
        // 두 사람씩 세 건
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_participant", Long.class)).isEqualTo(6);
        assertThat(lastLog("trigger_type")).isEqualTo("SCHEDULED");
        assertThat(lastLog("status")).isEqualTo("SUCCESS");
    }

    @Test
    void secondRunOverTheSameSheetChangesNothing() {
        var client = new FakeSheetsClient().month(SEPTEMBER, lunches(3));
        service(client).run(LedgerSyncService.SCHEDULED);

        var outcome = service(client).run(LedgerSyncService.SCHEDULED);

        assertThat(outcome.added()).isZero();
        assertThat(outcome.removed()).isZero();
        assertThat(outcome.unchanged()).isEqualTo(3);
        assertThat(activeRows()).isEqualTo(3);
    }

    @Test
    void anEmptiedMonthIsStoppedByTheBrakeAndNothingIsDeleted() {
        service(new FakeSheetsClient().month(SEPTEMBER, lunches(25))).run(LedgerSyncService.SCHEDULED);
        assertThat(activeRows()).isEqualTo(25);

        // 시트가 통째로 비었다 — 사람이 보고 있었다면 멈췄을 장면이다
        var outcome = service(new FakeSheetsClient().month(SEPTEMBER, List.of()))
                .run(LedgerSyncService.SCHEDULED);

        assertThat(outcome.applied()).isFalse();
        assertThat(outcome.failure()).isEqualTo("REMOVAL_LIMIT");
        assertThat(activeRows()).isEqualTo(25);
        assertThat(lastLog("status")).isEqualTo("FAILED");
        assertThat(lastLog("error_message")).isEqualTo("REMOVAL_LIMIT");
    }

    @Test
    void anOrdinaryEditIsNotMistakenForAWipe() {
        service(new FakeSheetsClient().month(SEPTEMBER, lunches(25))).run(LedgerSyncService.SCHEDULED);

        // 다섯 줄만 지운 보통의 수정 — 기준(20건) 아래라 그대로 반영한다
        var outcome = service(new FakeSheetsClient().month(SEPTEMBER, lunches(20)))
                .run(LedgerSyncService.SCHEDULED);

        assertThat(outcome.applied()).isTrue();
        assertThat(outcome.removed()).isEqualTo(5);
        assertThat(activeRows()).isEqualTo(20);
    }

    @Test
    void aBigButProportionateRemovalStillPassesWhenTheMonthIsSmallEnough() {
        var loose = properties();
        loose.setMaxRemovalsPerMonth(2);
        loose.setMaxRemovalRatio(0.9);
        service(new FakeSheetsClient().month(SEPTEMBER, lunches(10)), loose).run(LedgerSyncService.SCHEDULED);

        // 다섯 건은 건수 기준(2)은 넘지만 비율 기준(90%)은 넘지 않는다 — 둘 다 넘어야 걸린다
        var outcome = service(new FakeSheetsClient().month(SEPTEMBER, lunches(5)), loose)
                .run(LedgerSyncService.SCHEDULED);

        assertThat(outcome.applied()).isTrue();
        assertThat(activeRows()).isEqualTo(5);
    }

    @Test
    void aMonthWithABadRowKeepsAllOfItsRows() {
        service(new FakeSheetsClient().month(SEPTEMBER, lunches(3))).run(LedgerSyncService.SCHEDULED);

        var broken = new ArrayList<>(lunches(3));
        broken.add(FakeSheetsClient.row("4", "", "홍길동", "점심")); // 과제명이 없다
        var outcome = service(new FakeSheetsClient().month(SEPTEMBER, broken)).run(LedgerSyncService.SCHEDULED);

        assertThat(outcome.applied()).isFalse();
        assertThat(activeRows()).isEqualTo(3);
        assertThat(lastLog("error_message")).isEqualTo("NO_APPLICABLE_MONTHS");
    }

    @Test
    void losingPermissionLeavesTheCalendarAloneAndSaysWhy() {
        service(new FakeSheetsClient().month(SEPTEMBER, lunches(3))).run(LedgerSyncService.SCHEDULED);

        var outcome = service(new FakeSheetsClient()
                .failing(new SheetsApiException(SheetsApiException.Code.PERMISSION_DENIED)))
                .run(LedgerSyncService.SCHEDULED);

        assertThat(outcome.applied()).isFalse();
        assertThat(outcome.failure()).isEqualTo("SHEETS_PERMISSION_DENIED");
        assertThat(activeRows()).isEqualTo(3);
        assertThat(lastLog("status")).isEqualTo("FAILED");
        assertThat(lastLog("error_message")).isEqualTo("SHEETS_PERMISSION_DENIED");
    }

    @Test
    void aDocumentWithNoMonthTabsIsNotReadAsEveryMonthBeingEmpty() {
        service(new FakeSheetsClient().month(SEPTEMBER, lunches(3))).run(LedgerSyncService.SCHEDULED);

        var outcome = service(new FakeSheetsClient().month("복사용 시트", List.of()))
                .run(LedgerSyncService.SCHEDULED);

        assertThat(outcome.failure()).isEqualTo("SHEETS_NO_MONTH_TABS");
        assertThat(activeRows()).isEqualTo(3);
    }

    @Test
    void aMonthOutsideTheWindowIsLeftWhereItIs() {
        // 7월과 9월을 넣고, 창을 한 달로 좁힌 뒤 9월만 들어 있는 시트를 읽는다
        var narrow = properties();
        narrow.setMonthsBack(1);
        service(new FakeSheetsClient()
                .month(SEPTEMBER, lunches(2))
                .month("2026년 7월", List.of(FakeSheetsClient.row("3", "여름 과제", "홍길동", "저녁"))))
                .run(LedgerSyncService.SCHEDULED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM card_expense WHERE active=TRUE", Long.class))
                .isEqualTo(3);

        service(new FakeSheetsClient().month(SEPTEMBER, lunches(2)), narrow).run(LedgerSyncService.SCHEDULED);

        // 창 밖의 7월은 읽히지도, 지워지지도 않는다
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM card_expense WHERE source_document_id='ledger:2026-07' AND active=TRUE",
                Long.class)).isEqualTo(1);
    }
}
