package com.labcalendar.labcalendarbackend.expense.importing;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbookType;
import org.springframework.stereotype.Component;

import static com.labcalendar.labcalendarbackend.expense.importing.ExcelReadException.Code.*;

/** KAN-56 foundation: read A-D without interpreting months, names or persistence policy. */
@Component
public class ExcelLedgerReader {
    public static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_SHEETS = 200;
    private static final int MAX_PHYSICAL_ROWS = 100_000;

    public record RawCell(String text, boolean error) {}
    public record RawRow(int number, List<RawCell> cells) {
        public RawRow { cells = List.copyOf(cells); }
    }
    public record RawSheet(String name, List<RawRow> rows) {
        public RawSheet { rows = List.copyOf(rows); }
    }

    public List<RawSheet> read(String fileName, byte[] bytes) {
        if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new ExcelReadException(INVALID_FILE_TYPE);
        }
        if (bytes == null || bytes.length == 0) throw new ExcelReadException(EMPTY_FILE);
        if (bytes.length > MAX_FILE_BYTES) throw new ExcelReadException(FILE_TOO_LARGE);

        // No formula evaluator, temporary files, logging, or altered POI ZIP security settings.
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            if (workbook.getWorkbookType() != XSSFWorkbookType.XLSX) {
                throw new ExcelReadException(INVALID_FILE_TYPE);
            }
            if (workbook.getNumberOfSheets() == 0) throw new ExcelReadException(EMPTY_WORKBOOK);
            if (workbook.getNumberOfSheets() > MAX_SHEETS) throw new ExcelReadException(WORKBOOK_TOO_LARGE);
            DataFormatter formatter = new DataFormatter(Locale.KOREA);
            formatter.setUseCachedValuesForFormulaCells(true);
            List<RawSheet> sheets = new ArrayList<>();
            int rowCount = 0;
            for (var sheet : workbook) {
                List<RawRow> rows = new ArrayList<>();
                for (Row row : sheet) {
                    if (++rowCount > MAX_PHYSICAL_ROWS) throw new ExcelReadException(WORKBOOK_TOO_LARGE);
                    List<RawCell> cells = new ArrayList<>(4);
                    for (int column = 0; column < 4; column++) {
                        Cell cell = row.getCell(column);
                        if (cell == null) {
                            cells.add(new RawCell("", false));
                            continue;
                        }
                        CellType type = cell.getCellType();
                        if (type == CellType.FORMULA) {
                            if (!((XSSFCell) cell).getCTCell().isSetV()) {
                                throw new ExcelReadException(FORMULA_CACHE_MISSING);
                            }
                            type = cell.getCachedFormulaResultType();
                        }
                        cells.add(new RawCell(formatter.formatCellValue(cell), type == CellType.ERROR));
                    }
                    // Preserve original Excel row numbers; rows absent from XML are not synthesized.
                    rows.add(new RawRow(row.getRowNum() + 1, cells));
                }
                sheets.add(new RawSheet(sheet.getSheetName(), rows));
            }
            return List.copyOf(sheets);
        } catch (ExcelReadException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new ExcelReadException(INVALID_WORKBOOK);
        }
    }
}
