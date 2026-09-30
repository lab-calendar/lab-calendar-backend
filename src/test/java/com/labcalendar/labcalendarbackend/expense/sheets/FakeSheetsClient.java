package com.labcalendar.labcalendarbackend.expense.sheets;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stands in for Google (KAN-88).
 *
 * <p>Everything worth testing about the sync is above the HTTP boundary — which tabs are read,
 * what the brakes do, what gets written. Reaching the real API to find that out would make the
 * tests need a key, a network and somebody else's uptime.
 */
class FakeSheetsClient implements SheetsClient {

    private final Map<String, List<List<String>>> tabs = new LinkedHashMap<>();
    private RuntimeException failure;
    int valueCalls;

    /** The header every month tab needs to pass selection. */
    static List<String> header() {
        return List.of("일자", "과제", "인원", "점심/저녁");
    }

    static List<String> row(String day, String card, String names, String usage) {
        return List.of(day, card, names, usage);
    }

    FakeSheetsClient tab(String title, List<List<String>> rows) {
        tabs.put(title, rows);
        return this;
    }

    /** A month tab with a valid header and the given rows after it. */
    FakeSheetsClient month(String title, List<List<String>> rows) {
        var all = new ArrayList<List<String>>();
        all.add(header());
        all.addAll(rows);
        return tab(title, all);
    }

    FakeSheetsClient failing(RuntimeException failure) {
        this.failure = failure;
        return this;
    }

    @Override
    public List<String> tabTitles() {
        if (failure != null) throw failure;
        return List.copyOf(tabs.keySet());
    }

    @Override
    public List<TabValues> values(List<String> titles) {
        if (failure != null) throw failure;
        valueCalls++;
        return titles.stream().map(title -> new TabValues(title, tabs.getOrDefault(title, List.of()))).toList();
    }
}
