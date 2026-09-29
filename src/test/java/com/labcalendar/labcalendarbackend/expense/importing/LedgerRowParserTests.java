package com.labcalendar.labcalendarbackend.expense.importing;

import java.time.YearMonth;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.ByteArrayOutputStream;
import static org.assertj.core.api.Assertions.*;
import static com.labcalendar.labcalendarbackend.expense.importing.ExcelLedgerReader.*;
import static com.labcalendar.labcalendarbackend.expense.importing.LedgerRowParser.*;

class LedgerRowParserTests {
    private final LedgerRowParser parser = new LedgerRowParser();

    @Test
    void inheritsDateFromTemplateAndPreservesSparseRowNumbers() {
        var result = parse(row(2, "15(메모)", "", "", "점심"),
                row(5, "", "  가상   과제  ", "홍길동, 김철수", "저녁"),
                row(9, "", "", "", ""), row(12, "", "과제A", "홍길동", ""));
        assertThat(result.status()).isEqualTo(Status.READY);
        assertThat(result.entries()).extracting(Entry::row).containsExactly(5, 12);
        assertThat(result.entries()).extracting(e -> e.usedOn().toString()).containsOnly("2026-09-15");
        assertThat(result.entries().get(0).cardName()).isEqualTo("가상 과제");
        assertThat(result.entries().get(0).participantCount()).isEqualTo(2);
        assertThat(result.entries().get(0).memo()).isNull();
        assertThat(result.entries().get(1).usageType()).isEqualTo(UsageType.NONE);
        assertThat(result.entries().get(1).purpose()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"31", "0", "-1", "날짜 오류", "999999999999999999999"})
    void invalidDateResetsContextUntilValidDayAndBlocksWholeMonth(String invalid) {
        var result = parse(row(2, "1", "과제A", "홍길동", "점심"),
                row(3, invalid, "과제A", "홍길동", "점심"),
                row(4, "", "과제A", "홍길동", "점심"),
                row(5, "2", "과제A", "홍길동", "점심"));
        assertThat(result.status()).isEqualTo(Status.BLOCKED);
        assertThat(result.entries()).extracting(Entry::row).containsExactly(2, 5);
        assertThat(result.applicableEntries()).isEmpty();
        assertThat(result.problems()).extracting(Problem::code).containsExactly(Code.INVALID_DATE, Code.DATE_MISSING);
        assertThat(result.errorRowCount()).isEqualTo(2);
    }

    @Test
    void invalidTemplateDateAlsoResetsContext() {
        var result = parse(row(2, "1", "", "", ""), row(3, "31", "", "", "점심"),
                row(4, "", "과제A", "홍길동", "점심"));
        assertThat(result.problems()).extracting(Problem::code).containsExactly(Code.INVALID_DATE, Code.DATE_MISSING);
        assertThat(result.status()).isEqualTo(Status.BLOCKED);
    }

    @Test
    void leapYearAndMonthIsolation() {
        var leap = parser.parse(sheet(2024, 2, row(2, "29", "과제A", "홍길동", "점심")));
        var ordinary = parser.parse(sheet(2025, 2, row(2, "29", "과제A", "홍길동", "점심")));
        var missing = parser.parse(sheet(2026, 3, row(2, "", "과제A", "홍길동", "점심")));
        assertThat(leap.status()).isEqualTo(Status.READY);
        assertThat(ordinary.problems()).extracting(Problem::code).containsExactly(Code.INVALID_DATE);
        assertThat(missing.problems()).extracting(Problem::code).containsExactly(Code.DATE_MISSING);
    }

    @Test
    void warningDoesNotBlockButMissingCardDoesAndErrorsCountRowsOnce() {
        var warning = parse(row(2, "1", "과제A", "", ""));
        assertThat(warning.status()).isEqualTo(Status.READY);
        assertThat(warning.applicableEntries()).hasSize(1);
        assertThat(warning.entries().get(0).participantCount()).isZero();
        assertThat(warning.problems()).extracting(Problem::level).containsExactly(Level.WARNING);
        var error = parse(row(7, "", "", "홍길동", ""));
        assertThat(error.problems()).extracting(Problem::code).containsExactly(Code.DATE_MISSING, Code.CARD_MISSING);
        assertThat(error.errorRowCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"점심,LUNCH", "저녁,DINNER", "져녁,DINNER", "초과,OVERTIME", "저녁(초과근무),OVERTIME",
            "점심 초과,OVERTIME", "식대,OTHER", "출장,OTHER", "점/저,OTHER", "점ㅅ미,OTHER", "'점심,저녁',OTHER"})
    void classifiesPurposeWithoutChangingOriginal(String purpose, UsageType expected) {
        var entry = parse(row(2, "1", "과제A", "홍길동", purpose)).entries().get(0);
        assertThat(entry.usageType()).isEqualTo(expected);
        assertThat(entry.purpose()).isEqualTo(purpose);
    }

    @Test
    void retainsRawWhitespaceAndDistinguishesCardSpellings() {
        var result = parse(row(2, "1", "OO_과제명", "홍길동", "  점심  "),
                row(3, "1", "OO 과제명", "홍길동", " "), row(4, "1", "BRL/과제A", "홍길동", "저녁"));
        assertThat(result.entries()).extracting(Entry::cardName).containsExactly("OO_과제명", "OO 과제명", "BRL/과제A");
        assertThat(result.entries().get(0).purpose()).isEqualTo("  점심  ");
        assertThat(result.entries().get(0).usageType()).isEqualTo(UsageType.LUNCH);
        assertThat(result.entries().get(1).usageType()).isEqualTo(UsageType.NONE);
    }

    @Test
    void splitsParticipantsCleansAnnotationsAndPreservesRawMemo() {
        String raw = "홍길동(외부), 김철수./이영희!+박민수`\n최지우\\";
        var entry = parse(row(2, "1", "과제A", raw, "점심")).entries().get(0);
        assertThat(entry.participants()).containsExactly("홍길동", "김철수", "이영희", "박민수", "최지우");
        assertThat(entry.participantNamesRaw()).isEqualTo(raw);
        assertThat(entry.memo()).isEqualTo(raw);
        assertThat(entry.participantCount()).isEqualTo(5);
    }

    @Test
    void splitsOnlyKoreanNameWordsAndRetainsDuplicates() {
        var entry = parse(row(2, "1", "과제A", "홍길동 김철수/John Smith/홍길동", "점심")).entries().get(0);
        assertThat(entry.participants()).containsExactly("홍길동", "김철수", "John Smith", "홍길동");
    }

    @Test
    void removesHeadCountsButKeepsRawAndWarnsWhenNoNamesRemain() {
        var result = parse(row(2, "1", "과제A", "외부 2명, 19명", "점심"));
        assertThat(result.entries().get(0).participants()).isEmpty();
        assertThat(result.entries().get(0).memo()).isEqualTo("외부 2명, 19명");
        assertThat(result.problems()).extracting(Problem::code).containsExactly(Code.PARTICIPANTS_EMPTY);
        assertThat(result.status()).isEqualTo(Status.READY);
    }

    @Test
    void truncatesLongNameByCodePointsAndKeepsOriginal() {
        String raw = "😀".repeat(101);
        var entry = parse(row(2, "1", "과제A", raw, "점심")).entries().get(0);
        assertThat(entry.participants()).containsExactly("😀".repeat(100));
        assertThat(entry.memo()).isEqualTo(raw);
    }

    @Test
    void blocksOverlongCardRatherThanTruncatingIt() {
        assertThat(parse(row(2, "1", "가".repeat(101), "홍길동", "점심")).problems())
                .extracting(Problem::code).containsExactly(Code.CARD_TOO_LONG);
        assertThat(parse(row(2, "1", "가".repeat(100), "홍길동", "점심")).status()).isEqualTo(Status.READY);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void errorCellsNeverBecomeExpensesOrLeakCellContents(int column) {
        var cells = new java.util.ArrayList<>(row(2, "1", "과제A", "홍길동", "점심").cells());
        cells.set(column, new RawCell("private-cell-value", true));
        var result = parse(new RawRow(2, cells), row(3, "", "과제A", "홍길동", "점심"));
        assertThat(result.status()).isEqualTo(Status.BLOCKED);
        assertThat(result.problems().toString()).doesNotContain("private-cell-value", "홍길동");
        assertThat(result.problems().get(0).code()).isEqualTo(Code.CELL_ERROR);
        assertThat(result.entries()).hasSize(column == 0 ? 0 : 1);
    }

    @Test
    void validEmptyMonthDiffersFromErrorEmptyMonth() {
        assertThat(parse(row(2, "", "", "", "점심")).status()).isEqualTo(Status.READY);
        assertThat(parse().entries()).isEmpty();
        assertThat(parse(row(2, "31", "과제A", "홍길동", "점심")).status()).isEqualTo(Status.BLOCKED);
    }

    @Test
    void workbookPipelineKeepsHealthyMonthApplicableAlongsideBlockedMonth() throws Exception {
        try (var book = new XSSFWorkbook()) {
            for (String name : List.of("2026년 9월", "2026년 8월")) {
                var sheet = book.createSheet(name);
                var header = sheet.createRow(0);
                header.createCell(1).setCellValue("과제명");
                header.createCell(2).setCellValue("인원");
                header.createCell(3).setCellValue("점심 or 저녁");
                var row = sheet.createRow(6);
                row.createCell(0).setCellValue(31);
                row.createCell(1).setCellValue("가상 과제");
                row.createCell(2).setCellValue("홍길동");
                row.createCell(3).setCellValue("점심");
            }
            var out = new ByteArrayOutputStream();
            book.write(out);
            var results = parser.parse(new LedgerSheetSelector().select(new ExcelLedgerReader().read("test.xlsx", out.toByteArray())));
            assertThat(results).extracting(ParsedMonth::status).containsExactly(Status.BLOCKED, Status.READY);
            assertThat(results.get(0).applicableEntries()).isEmpty();
            assertThat(results.get(1).applicableEntries()).hasSize(1);
            assertThat(results.get(1).entries().get(0).row()).isEqualTo(7);
        }
    }

    private ParsedMonth parse(RawRow... rows) { return parser.parse(sheet(2026, 9, rows)); }
    private LedgerSheetSelector.MonthSheet sheet(int year, int month, RawRow... rows) {
        return new LedgerSheetSelector.MonthSheet(year + "년 " + month + "월", YearMonth.of(year, month), List.of(rows));
    }
    private RawRow row(int number, String... values) {
        return new RawRow(number, Stream.of(values).map(v -> new RawCell(v, false)).toList());
    }
}
