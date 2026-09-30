package com.labcalendar.labcalendarbackend.expense.sheets;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import com.labcalendar.labcalendarbackend.expense.importing.ExcelLedgerReader.RawCell;
import com.labcalendar.labcalendarbackend.expense.importing.ExcelLedgerReader.RawRow;
import com.labcalendar.labcalendarbackend.expense.importing.ExcelLedgerReader.RawSheet;
import com.labcalendar.labcalendarbackend.expense.importing.LedgerSheetSelector;

/**
 * Reads the spreadsheet into the same shape the uploaded workbook produces (KAN-88).
 *
 * <p>The import pipeline has exactly one piece that knows where the ledger came from — the reader.
 * Selection, parsing and reconciliation all work on {@code RawSheet}, so producing one from the
 * Sheets API is the whole of the integration. A month that was uploaded as a file yesterday and
 * arrives from the sheet today reconciles against the same rows, because the identity downstream
 * is the month, not the source.
 *
 * <p>Only the recent months are fetched. The document keeps one tab per month going back years,
 * and those old tabs do not change; reading all of them every hour would spend the day's quota
 * confirming what is already stored.
 */
class SheetsLedgerReader {

    /**
     * Sheets has no cell type in a formatted read — an error arrives as the text a person sees.
     * These are the strings the API returns for one, and the parser has to treat them as errors
     * rather than as a card name.
     */
    private static final Pattern ERROR_TEXT = Pattern.compile(
            "^#(?:REF!|VALUE!|DIV/0!|N/A|NAME\\?|NUM!|NULL!|ERROR!|GETTING_DATA)$");

    /** A ceiling the configuration cannot talk its way past. */
    private static final int MAX_TABS_PER_RUN = 120;
    private static final int COLUMNS = 4;

    private final SheetsClient client;
    private final SheetsProperties properties;
    private final Clock clock;

    SheetsLedgerReader(SheetsClient client, SheetsProperties properties, Clock clock) {
        this.client = client;
        this.properties = properties;
        this.clock = clock;
    }

    /** The month tabs inside the window, oldest first. Never empty — it throws instead. */
    List<RawSheet> read() {
        Set<YearMonth> window = window();
        var wanted = new ArrayList<String>();
        var claimed = new LinkedHashSet<YearMonth>();

        for (String title : client.tabTitles()) {
            var month = LedgerSheetSelector.lenientMonthOf(title);
            if (month.isEmpty() || !window.contains(month.get())) continue;
            /*
             * Two tabs naming the same month is a mistake in the document, not something to
             * resolve here by guessing. Both are fetched and the selector rejects the pair, so the
             * run stops with a reason instead of silently taking one of them.
             */
            claimed.add(month.get());
            wanted.add(title);
            if (wanted.size() > MAX_TABS_PER_RUN) {
                throw new SheetsApiException(SheetsApiException.Code.RESPONSE_TOO_LARGE);
            }
        }

        /*
         * Nothing to read is not the same as "every month is now empty". Saying so here keeps the
         * caller from handing an empty selection to reconciliation, which would read it as the
         * lab having deleted everything (KAN-89).
         */
        if (wanted.isEmpty()) throw new SheetsApiException(SheetsApiException.Code.NO_MONTH_TABS);

        var sheets = new ArrayList<RawSheet>();
        for (SheetsClient.TabValues tab : client.values(wanted)) {
            sheets.add(new RawSheet(tab.title(), rows(tab.rows())));
        }
        return List.copyOf(sheets);
    }

    /** The current month and the ones before it, as many as the settings ask for. */
    private Set<YearMonth> window() {
        YearMonth current = YearMonth.from(LocalDate.ofInstant(clock.instant(), zone()));
        var months = new LinkedHashSet<YearMonth>();
        for (int back = 0; back < properties.getMonthsBack(); back++) months.add(current.minusMonths(back));
        return months;
    }

    /**
     * The lab writes and reads the sheet in its own day, so the window turns over at midnight
     * there rather than at UTC midnight. Storage stays UTC; only this boundary is local.
     */
    private ZoneId zone() {
        return com.labcalendar.labcalendarbackend.config.TimeConfig.SERVICE_ZONE;
    }

    private List<RawRow> rows(List<List<String>> values) {
        var rows = new ArrayList<RawRow>(values.size());
        for (int index = 0; index < values.size(); index++) {
            List<String> raw = values.get(index);
            var cells = new ArrayList<RawCell>(COLUMNS);
            for (int column = 0; column < COLUMNS; column++) {
                // A row stops at its last filled cell, so a short row means empty columns after it.
                String text = column < raw.size() && raw.get(column) != null ? raw.get(column) : "";
                cells.add(new RawCell(text, ERROR_TEXT.matcher(text.strip()).matches()));
            }
            // The range starts at A1, so position and sheet row number are the same thing.
            rows.add(new RawRow(index + 1, cells));
        }
        return rows;
    }
}
