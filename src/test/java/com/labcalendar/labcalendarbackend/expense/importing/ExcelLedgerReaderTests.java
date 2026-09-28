package com.labcalendar.labcalendarbackend.expense.importing;

import java.io.ByteArrayOutputStream;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbookType;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.labcalendar.labcalendarbackend.expense.importing.ExcelReadException.Code.*;

class ExcelLedgerReaderTests {
    private final ExcelLedgerReader reader = new ExcelLedgerReader();

    @Test
    void preservesSheetNamesForTheFutureClassifier() throws Exception {
        var cases = java.util.stream.Stream.concat(
                SheetNameCases.DESIGN_CASES.stream(), SheetNameCases.PROPOSED_CASES.stream()).toList();
        try (var workbook = new XSSFWorkbook()) {
            for (var example : cases) workbook.createSheet(example.name());
            assertThat(reader.read("sheet-names.xlsx", bytes(workbook)))
                    .extracting(ExcelLedgerReader.RawSheet::name)
                    .containsExactlyElementsOf(cases.stream().map(SheetNameCases.NameCase::name).toList());
        }
    }

    private byte[] bytes(XSSFWorkbook workbook) throws Exception {
        var output = new ByteArrayOutputStream();
        workbook.write(output);
        return output.toByteArray();
    }

    @Test
    void readsSheetNamesFourColumnsAndOriginalRowNumbers() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("2026년 9월");
            var header = sheet.createRow(0);
            header.createCell(1).setCellValue("과제명");
            var row = sheet.createRow(4);
            row.createCell(0).setCellValue(15);
            row.createCell(1).setCellValue("가상 과제");
            row.createCell(2).setCellValue("가상인물A, 가상인물B");
            row.createCell(6).setCellFormula("1/0"); // Ignored: outside A-D, even without a cache.
            workbook.createSheet("복사용 시트");
            var sheets = reader.read("example.XLSX", bytes(workbook));
            assertThat(sheets).extracting(ExcelLedgerReader.RawSheet::name)
                    .containsExactly("2026년 9월", "복사용 시트");
            assertThat(sheets.get(0).rows()).extracting(ExcelLedgerReader.RawRow::number).containsExactly(1, 5);
            assertThat(sheets.get(0).rows().get(1).cells()).extracting(ExcelLedgerReader.RawCell::text)
                    .containsExactly("15", "가상 과제", "가상인물A, 가상인물B", "");
            assertThat(sheets.get(1).rows()).isEmpty();
        }
    }

    @Test
    void usesCachedFormulaResultWithoutRecalculating() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var row = workbook.createSheet("sheet").createRow(0);
            var cell = row.createCell(0);
            cell.setCellFormula("1+1");
            cell.setCellValue(9); // Deliberately stale cached value: reader must not evaluate 1+1.
            assertThat(reader.read("sample.xlsx", bytes(workbook)).get(0).rows().get(0).cells().get(0).text())
                    .isEqualTo("9");
        }
    }

    @Test
    void rejectsFormulaWithoutStoredResult() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            workbook.createSheet("sheet").createRow(0).createCell(0).setCellFormula("1+1");
            assertThatThrownBy(() -> reader.read("sample.xlsx", bytes(workbook)))
                    .isInstanceOf(ExcelReadException.class).hasMessage(FORMULA_CACHE_MISSING.name());
        }
    }

    @Test
    void preservesErrorCellsForTheLaterParser() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            workbook.createSheet("sheet").createRow(0).createCell(0)
                    .setCellErrorValue(FormulaError.DIV0.getCode());
            var cell = reader.read("sample.xlsx", bytes(workbook)).get(0).rows().get(0).cells().get(0);
            assertThat(cell.error()).isTrue();
            assertThat(cell.text()).isEqualTo("#DIV/0!");
        }
    }

    @Test
    void rejectsEmptyOversizedAndWrongExtensionFiles() {
        assertThatThrownBy(() -> reader.read("sample.xlsx", new byte[0])).hasMessage(EMPTY_FILE.name());
        assertThatThrownBy(() -> reader.read("sample.xlsx", new byte[ExcelLedgerReader.MAX_FILE_BYTES + 1]))
                .hasMessage(FILE_TOO_LARGE.name());
        assertThatThrownBy(() -> reader.read("sample.xls", new byte[1])).hasMessage(INVALID_FILE_TYPE.name());
    }

    @Test
    void rejectsCorruptContentWithoutEchoingInput() {
        assertThatThrownBy(() -> reader.read("private-name.xlsx", "private contents".getBytes()))
                .isInstanceOf(ExcelReadException.class).hasMessage(INVALID_WORKBOOK.name()).hasNoCause();
    }

    @Test
    void rejectsMacroWorkbookRenamedToXlsx() throws Exception {
        try (var workbook = new XSSFWorkbook(XSSFWorkbookType.XLSM)) {
            workbook.createSheet("sheet");
            assertThatThrownBy(() -> reader.read("renamed.xlsx", bytes(workbook)))
                    .hasMessage(INVALID_FILE_TYPE.name());
        }
    }

    @Test
    void rejectsWorkbookWithoutSheets() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            assertThatThrownBy(() -> reader.read("empty.xlsx", bytes(workbook)))
                    .hasMessage(EMPTY_WORKBOOK.name());
        }
    }
}
