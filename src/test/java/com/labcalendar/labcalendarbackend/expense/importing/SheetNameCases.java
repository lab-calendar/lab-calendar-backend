package com.labcalendar.labcalendarbackend.expense.importing;

import java.time.YearMonth;
import java.util.List;

/** Fixtures for the future KAN-56 classifier, not a classifier implementation. */
final class SheetNameCases {
    enum Outcome { MONTH, SKIP, ERROR }
    record NameCase(String name, Outcome expected, YearMonth month, String reason) {}

    // Based on the current KAN-54 draft. SKIP is an observable result, never silent deletion.
    static final List<NameCase> DESIGN_CASES = List.of(
            month("2026년 9월", 2026, 9),
            month("2024 7월", 2024, 7),
            month("2026년2월", 2026, 2),
            month("2024 01월", 2024, 1),
            month("2026년 12월", 2026, 12),
            month("2026  9월", 2026, 9),
            skip("7월", "YEAR_MISSING"),
            skip("08월", "YEAR_MISSING"),
            skip("복사용 시트", "NOT_MONTH_SHEET"),
            skip("복사용시트2", "NOT_MONTH_SHEET"),
            skip("메모", "NOT_MONTH_SHEET"),
            skip("2026년 9월 사본", "NOT_MONTH_SHEET"));

    // These recommended outcomes need agreement; do not silently make them production policy.
    static final List<NameCase> PROPOSED_CASES = List.of(
            month(" 2026년 9월 ", 2026, 9),
            error("2026년 0월", "INVALID_MONTH"),
            error("2026년 13월", "INVALID_MONTH"),
            error("0000년 9월", "INVALID_YEAR"),
            error("2026년 009월", "INVALID_MONTH_FORMAT"),
            error("20269월", "INVALID_MONTH_FORMAT"));

    private static NameCase month(String name, int year, int month) {
        return new NameCase(name, Outcome.MONTH, YearMonth.of(year, month), null);
    }
    private static NameCase skip(String name, String reason) {
        return new NameCase(name, Outcome.SKIP, null, reason);
    }
    private static NameCase error(String name, String reason) {
        return new NameCase(name, Outcome.ERROR, null, reason);
    }
    private SheetNameCases() {}
}
