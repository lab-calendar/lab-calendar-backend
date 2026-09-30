package com.labcalendar.labcalendarbackend.expense.sheets;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.labcalendar.labcalendarbackend.expense.importing.ExcelLedgerReader.RawSheet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** KAN-88: the sheet has to arrive in the same shape an uploaded workbook does. */
class SheetsLedgerReaderTests {

    /** 2026-09-30 in Seoul, so the three-month window is September, August and July. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-30T01:00:00Z"), ZoneId.of("UTC"));

    private SheetsLedgerReader reader(SheetsClient client, int monthsBack) {
        var properties = new SheetsProperties();
        properties.setMonthsBack(monthsBack);
        return new SheetsLedgerReader(client, properties, CLOCK);
    }

    @Test
    void readsOnlyTheMonthsInTheWindowAndLeavesTemplatesAlone() {
        var client = new FakeSheetsClient()
                .month("복사용 시트", List.of())
                .month("복사용시트2", List.of())
                .month("2026년 9월", List.of())
                .month("2026년8월", List.of())   // 공백이 빠진 표기도 같은 달이다
                .month("2026년 7월", List.of())
                .month("2026년 6월", List.of()); // 창 밖

        List<RawSheet> sheets = reader(client, 3).read();

        assertThat(sheets).extracting(RawSheet::name)
                .containsExactly("2026년 9월", "2026년8월", "2026년 7월");
    }

    @Test
    void keepsSheetRowNumbersAndPadsShortRowsToFourColumns() {
        var client = new FakeSheetsClient().month("2026년 9월", List.of(
                List.of("1", "가상 과제"),                       // 짧은 행 — 뒤 두 칸은 빈 칸
                List.of("2", "다른 과제", "홍길동", "점심")));

        RawSheet sheet = reader(client, 1).read().get(0);

        assertThat(sheet.rows()).extracting(row -> row.number()).containsExactly(1, 2, 3);
        assertThat(sheet.rows().get(1).cells()).hasSize(4);
        assertThat(sheet.rows().get(1).cells().get(2).text()).isEmpty();
        assertThat(sheet.rows().get(2).cells().get(3).text()).isEqualTo("점심");
    }

    @Test
    void readsFormulaErrorsAsErrorCellsRatherThanAsText() {
        var client = new FakeSheetsClient().month("2026년 9월", List.of(
                List.of("1", "#REF!", "홍길동", "점심"),
                List.of("2", "#N/A", "홍길동", "점심"),
                List.of("3", "#해시태그", "홍길동", "점심")));

        RawSheet sheet = reader(client, 1).read().get(0);

        assertThat(sheet.rows().get(1).cells().get(1).error()).isTrue();
        assertThat(sheet.rows().get(2).cells().get(1).error()).isTrue();
        // 우물 정자로 시작할 뿐인 보통 글자는 오류가 아니다
        assertThat(sheet.rows().get(3).cells().get(1).error()).isFalse();
    }

    @Test
    void noMonthTabInTheWindowIsRefusedRatherThanReadAsAnEmptyLedger() {
        var client = new FakeSheetsClient().month("복사용 시트", List.of()).month("2019년 3월", List.of());

        assertThatThrownBy(() -> reader(client, 3).read())
                .isInstanceOf(SheetsApiException.class)
                .extracting(failure -> ((SheetsApiException) failure).code())
                .isEqualTo(SheetsApiException.Code.NO_MONTH_TABS);
    }

    @Test
    void passesTheClientFailureThroughUntouched() {
        var client = new FakeSheetsClient()
                .failing(new SheetsApiException(SheetsApiException.Code.PERMISSION_DENIED));

        assertThatThrownBy(() -> reader(client, 3).read())
                .isInstanceOf(SheetsApiException.class)
                .extracting(failure -> ((SheetsApiException) failure).code())
                .isEqualTo(SheetsApiException.Code.PERMISSION_DENIED);
    }
}
