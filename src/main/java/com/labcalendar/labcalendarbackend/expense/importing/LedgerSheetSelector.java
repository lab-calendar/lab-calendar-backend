package com.labcalendar.labcalendarbackend.expense.importing;

import java.time.DateTimeException;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import com.labcalendar.labcalendarbackend.expense.importing.ExcelLedgerReader.RawRow;
import com.labcalendar.labcalendarbackend.expense.importing.ExcelLedgerReader.RawSheet;
import static com.labcalendar.labcalendarbackend.expense.importing.ExcelReadException.Code.*;

/** Applies KAN-54 section 3.3 without interpreting ledger entries (KAN-57). */
@Component
public class LedgerSheetSelector {
    // Keep the merged contract verbatim; stricter spelling rules require a contract change.
    private static final Pattern MONTH = Pattern.compile("^(\\d{4})\\s*년?\\s*0?(\\d{1,2})\\s*월$");
    private static final Pattern YEARLESS = Pattern.compile("^\\d{1,2}\\s*월$");

    public enum SkipReason { YEAR_MISSING, NOT_MONTH_SHEET }
    public record SkippedSheet(String sheet, SkipReason reason) {}
    public record MonthSheet(String sheet, YearMonth month, List<RawRow> rows) {
        public MonthSheet { rows = List.copyOf(rows); }
    }
    public record Selection(List<MonthSheet> months, List<SkippedSheet> skippedSheets) {
        public Selection {
            months = List.copyOf(months);
            skippedSheets = List.copyOf(skippedSheets);
        }
    }

    /**
     * The month a tab name names, or empty when it does not name one (KAN-88).
     *
     * <p>Lenient on purpose, and separate from {@link #select}: this answers "is this tab worth
     * fetching at all", which is asked before anything has been read. A name that matches the
     * shape but carries an impossible number ({@code 2026년 13월}) is "not a month" here, and is
     * still rejected loudly by {@code select} once its rows are in hand.
     */
    public static java.util.Optional<YearMonth> lenientMonthOf(String sheetName) {
        if (sheetName == null) return java.util.Optional.empty();
        var matcher = MONTH.matcher(sheetName);
        if (!matcher.matches()) return java.util.Optional.empty();
        try {
            return java.util.Optional.of(YearMonth.of(
                    Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))));
        } catch (DateTimeException | NumberFormatException notAMonth) {
            return java.util.Optional.empty();
        }
    }

    public Selection select(List<RawSheet> sheets) {
        var months = new ArrayList<MonthSheet>();
        var skipped = new ArrayList<SkippedSheet>();
        var seen = new HashSet<YearMonth>();
        for (var sheet : sheets) {
            var matcher = MONTH.matcher(sheet.name());
            if (!matcher.matches()) {
                skipped.add(new SkippedSheet(sheet.name(), YEARLESS.matcher(sheet.name()).matches()
                        ? SkipReason.YEAR_MISSING : SkipReason.NOT_MONTH_SHEET));
                continue;
            }
            YearMonth month;
            try {
                month = YearMonth.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
            } catch (DateTimeException exception) {
                throw new ExcelReadException(INVALID_MONTH);
            }
            if (!seen.add(month)) throw new ExcelReadException(DUPLICATE_MONTH);
            var header = sheet.rows().stream().filter(row -> row.number() == 1).findFirst()
                    .orElseThrow(() -> new ExcelReadException(INVALID_LAYOUT));
            if (!contains(header, 1, "과제") || !contains(header, 2, "인원")
                    || !(contains(header, 3, "점심") || contains(header, 3, "저녁"))) {
                throw new ExcelReadException(INVALID_LAYOUT);
            }
            months.add(new MonthSheet(sheet.name(), month,
                    sheet.rows().stream().filter(row -> row.number() > 1).toList()));
        }
        if (months.isEmpty()) throw new ExcelReadException(NO_MONTH_SHEETS);
        return new Selection(months, skipped);
    }

    private boolean contains(RawRow row, int column, String token) {
        return row.cells().size() > column && !row.cells().get(column).error()
                && row.cells().get(column).text().contains(token);
    }
}
