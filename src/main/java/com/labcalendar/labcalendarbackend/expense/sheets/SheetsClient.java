package com.labcalendar.labcalendarbackend.expense.sheets;

import java.util.List;

/**
 * The only part of the sync that talks to Google (KAN-88).
 *
 * <p>Kept as an interface so everything above it — month selection, parsing, the safety brakes —
 * can be tested without a network or a key. The tests stand a fake here.
 */
interface SheetsClient {

    /** One tab's cells, columns A to D, starting at row 1. Rows are in sheet order. */
    record TabValues(String title, List<List<String>> rows) {
        public TabValues {
            rows = rows.stream().map(List::copyOf).toList();
        }
    }

    /** Every tab title in the document, in the order the document keeps them. */
    List<String> tabTitles();

    /** Columns A to D of each named tab. The result matches the argument, tab for tab. */
    List<TabValues> values(List<String> titles);
}
