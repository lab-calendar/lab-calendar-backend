package com.labcalendar.labcalendarbackend.expense.importing;

import java.io.ByteArrayOutputStream;
import java.time.YearMonth;
import java.util.List;
import java.util.stream.Stream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static com.labcalendar.labcalendarbackend.expense.importing.ExcelLedgerReader.*;

class LedgerSheetSelectorTests {
    private final LedgerSheetSelector selector = new LedgerSheetSelector();

    static Stream<SheetNameCases.NameCase> names() { return SheetNameCases.DESIGN_CASES.stream(); }

    @ParameterizedTest
    @MethodSource("names")
    void followsDesignedNames(SheetNameCases.NameCase example) {
        var sheets = new java.util.ArrayList<RawSheet>();
        sheets.add(sheet(example.name()));
        if (example.expected() == SheetNameCases.Outcome.SKIP) sheets.add(sheet("2025년 1월"));
        var result = selector.select(sheets);
        if (example.expected() == SheetNameCases.Outcome.MONTH) {
            assertThat(result.months().get(0).month()).isEqualTo(example.month());
            assertThat(result.skippedSheets()).isEmpty();
        } else {
            assertThat(result.skippedSheets()).containsExactly(new LedgerSheetSelector.SkippedSheet(
                    example.name(), LedgerSheetSelector.SkipReason.valueOf(example.reason())));
        }
    }

    @Test
    void rejectsDuplicateMonthsWithDifferentSpelling() {
        assertThatThrownBy(() -> selector.select(List.of(sheet("2026년 9월"), sheet("2026 09월"))))
                .hasMessage("DUPLICATE_MONTH");
    }

    @Test
    void rejectsAllSkippedIncludingEmptyInput() {
        assertThatThrownBy(() -> selector.select(List.of(sheet("7월"), sheet("복사용 시트"))))
                .hasMessage("NO_MONTH_SHEETS");
        assertThatThrownBy(() -> selector.select(List.of())).hasMessage("NO_MONTH_SHEETS");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026년 0월", "2026년 13월"})
    void rejectsImpossibleMonthEvenAlongsideValidMonth(String name) {
        assertThatThrownBy(() -> selector.select(List.of(sheet("2026년 9월"), sheet(name))))
                .isInstanceOf(ExcelReadException.class).hasMessage("INVALID_MONTH").hasNoCause();
    }

    @Test
    void retainsMergedRegexRatherThanUnapprovedProposals() {
        assertThat(selector.select(List.of(sheet("2026년 009월"))).months().get(0).month())
                .isEqualTo(YearMonth.of(2026, 9));
        assertThat(selector.select(List.of(sheet("20269월"))).months().get(0).month())
                .isEqualTo(YearMonth.of(2026, 9));
        assertThat(selector.select(List.of(sheet("0000년 9월"))).months().get(0).month())
                .isEqualTo(YearMonth.of(0, 9));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void rejectsMissingOrErrorHeaders(int column) {
        for (var replacement : List.of(new RawCell("", false), new RawCell("과제 인원 점심", true))) {
            var cells = new java.util.ArrayList<>(sheet("2026년 9월").rows().get(0).cells());
            cells.set(column, replacement);
            assertThatThrownBy(() -> selector.select(List.of(new RawSheet("2026년 9월",
                    List.of(new RawRow(1, cells)))))).hasMessage("INVALID_LAYOUT");
        }
    }

    @Test
    void rejectsAbsentFirstRowAndShiftedColumns() {
        assertThatThrownBy(() -> selector.select(List.of(new RawSheet("2026년 9월", List.of()))))
                .hasMessage("INVALID_LAYOUT");
        assertThatThrownBy(() -> selector.select(List.of(new RawSheet("2026년 9월",
                List.of(new RawRow(2, sheet("2026년 9월").rows().get(0).cells()))))))
                .hasMessage("INVALID_LAYOUT");
        assertThatThrownBy(() -> selector.select(List.of(new RawSheet("2026년 9월",
                List.of(row(1, "", "날짜", "과제명", "인원")))))).hasMessage("INVALID_LAYOUT");
    }

    @Test
    void readsWorkbookPreservingOrderSparseRowsAndEmptyValidMonth() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            for (String name : List.of("2026년 9월", "7월", "2026년 8월", "복사용 시트")) {
                var sheet = workbook.createSheet(name);
                if (!name.startsWith("2026")) continue;
                var header = sheet.createRow(0);
                header.createCell(0).setCellValue("잘못된 A1도 무시");
                header.createCell(1).setCellValue("과제명 or 출장");
                header.createCell(2).setCellValue("인원");
                header.createCell(3).setCellValue("저녁");
            }
            workbook.getSheetAt(0).createRow(8).createCell(1).setCellValue("가상 과제");
            var out = new ByteArrayOutputStream();
            workbook.write(out);
            var result = selector.select(new ExcelLedgerReader().read("test.xlsx", out.toByteArray()));
            assertThat(result.months()).extracting(LedgerSheetSelector.MonthSheet::month)
                    .containsExactly(YearMonth.of(2026, 9), YearMonth.of(2026, 8));
            assertThat(result.months().get(0).rows()).extracting(RawRow::number).containsExactly(9);
            assertThat(result.months().get(1).rows()).isEmpty();
            assertThat(result.skippedSheets()).extracting(LedgerSheetSelector.SkippedSheet::sheet)
                    .containsExactly("7월", "복사용 시트");
        }
    }

    private static RawSheet sheet(String name) {
        return new RawSheet(name, List.of(row(1, "ignored", "과제명", "인원", "점심 or 저녁")));
    }
    private static RawRow row(int number, String... values) {
        return new RawRow(number, Stream.of(values).map(value -> new RawCell(value, false)).toList());
    }
}
