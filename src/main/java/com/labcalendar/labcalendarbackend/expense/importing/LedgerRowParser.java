package com.labcalendar.labcalendarbackend.expense.importing;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import com.labcalendar.labcalendarbackend.expense.importing.ExcelLedgerReader.RawCell;
import com.labcalendar.labcalendarbackend.expense.importing.ExcelLedgerReader.RawRow;
import com.labcalendar.labcalendarbackend.expense.importing.LedgerSheetSelector.MonthSheet;

/** Pure interpretation of KAN-54 section 4. No persistence or member lookup. */
@Component
public class LedgerRowParser {
    private static final Pattern DAY = Pattern.compile("^([0-9]+)");
    private static final Pattern SPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern PARENTHESES = Pattern.compile("\\([^()]*\\)");
    private static final Pattern COUNT = Pattern.compile("^(?:외부\\s*)?[0-9]+\\s*명$");

    public enum UsageType { LUNCH, DINNER, OVERTIME, NONE, OTHER }
    public enum Level { WARNING, ERROR }
    public enum Code { DATE_MISSING, INVALID_DATE, CARD_MISSING, CARD_TOO_LONG, PARTICIPANTS_EMPTY, CELL_ERROR }
    public enum Status { READY, BLOCKED }
    public record Problem(String sheet, int row, Level level, Code code) {}
    public record Entry(int row, LocalDate usedOn, String cardName, String purpose,
                        UsageType usageType, String participantNamesRaw, List<String> participants,
                        String memo) {
        public Entry { participants = List.copyOf(participants); }
        public int participantCount() { return participants.size(); }
    }
    public record ParsedMonth(String sheet, YearMonth month, List<Entry> entries, List<Problem> problems) {
        public ParsedMonth { entries = List.copyOf(entries); problems = List.copyOf(problems); }
        public Status status() {
            return problems.stream().anyMatch(p -> p.level() == Level.ERROR) ? Status.BLOCKED : Status.READY;
        }
        /** Downstream synchronization must never apply even valid rows from a blocked month. */
        public List<Entry> applicableEntries() { return status() == Status.READY ? entries : List.of(); }
        public long errorRowCount() {
            return problems.stream().filter(p -> p.level() == Level.ERROR).map(Problem::row).distinct().count();
        }
    }

    public List<ParsedMonth> parse(LedgerSheetSelector.Selection selection) {
        return selection.months().stream().map(this::parse).toList();
    }

    public ParsedMonth parse(MonthSheet sheet) {
        var entries = new ArrayList<Entry>();
        var problems = new ArrayList<Problem>();
        LocalDate currentDate = null;
        // Each call has its own date context; never inherit from a different month.
        for (RawRow row : sheet.rows()) {
            RawCell day = cell(row, 0);
            boolean dateInvalid = false;
            if (day.error()) {
                currentDate = null;
                dateInvalid = true;
            } else if (!day.text().isBlank()) {
                currentDate = date(sheet.month(), day.text());
                dateInvalid = currentDate == null;
            }
            if (row.cells().stream().limit(4).anyMatch(RawCell::error)) {
                problems.add(problem(sheet, row, Level.ERROR, Code.CELL_ERROR));
                continue;
            }
            if (dateInvalid) problems.add(problem(sheet, row, Level.ERROR, Code.INVALID_DATE));
            String card = normalize(cell(row, 1).text());
            String rawNames = cell(row, 2).text();
            // Template rows can change date context but do not create an expense.
            if (card.isEmpty() && rawNames.isBlank()) continue;
            boolean invalid = dateInvalid;
            if (currentDate == null && !dateInvalid) {
                problems.add(problem(sheet, row, Level.ERROR, Code.DATE_MISSING));
                invalid = true;
            }
            if (card.isEmpty()) {
                problems.add(problem(sheet, row, Level.ERROR, Code.CARD_MISSING));
                invalid = true;
            } else if (card.codePointCount(0, card.length()) > 100) {
                // Do not silently truncate or let a later DB error roll back unrelated months.
                problems.add(problem(sheet, row, Level.ERROR, Code.CARD_TOO_LONG));
                invalid = true;
            }
            if (invalid) continue;
            List<String> names = participants(rawNames);
            if (names.isEmpty()) problems.add(problem(sheet, row, Level.WARNING, Code.PARTICIPANTS_EMPTY));
            String purpose = cell(row, 3).text();
            String memo = rawNames.equals(String.join(", ", names)) ? null : rawNames;
            entries.add(new Entry(row.number(), currentDate, card, purpose, usage(purpose), rawNames, names, memo));
        }
        return new ParsedMonth(sheet.sheet(), sheet.month(), entries, problems);
    }

    private LocalDate date(YearMonth month, String raw) {
        var match = DAY.matcher(raw.strip());
        if (!match.find()) return null;
        try {
            int day = Integer.parseInt(match.group(1));
            return month.isValidDay(day) ? month.atDay(day) : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private UsageType usage(String raw) {
        String value = normalize(raw);
        if (value.contains("초과")) return UsageType.OVERTIME;
        return switch (value) {
            case "" -> UsageType.NONE;
            case "점심" -> UsageType.LUNCH;
            case "저녁", "져녁" -> UsageType.DINNER;
            default -> UsageType.OTHER;
        };
    }

    private List<String> participants(String raw) {
        var names = new ArrayList<String>();
        for (String part : raw.split("[,/+\\r\\n]")) {
            String cleaned = part;
            String previous;
            do {
                previous = cleaned;
                cleaned = PARENTHESES.matcher(cleaned).replaceAll("");
            } while (!previous.equals(cleaned));
            cleaned = normalize(cleaned).replaceAll("[.`\\\\!]+$", "").strip();
            if (cleaned.isEmpty() || COUNT.matcher(cleaned).matches()) continue;
            String[] words = cleaned.split(" ");
            boolean koreanNames = words.length > 1;
            for (String word : words) koreanNames &= word.matches("[가-힣]{2,4}");
            if (koreanNames) {
                names.addAll(List.of(words));
            } else {
                int length = cleaned.codePointCount(0, cleaned.length());
                names.add(length > 100 ? cleaned.substring(0, cleaned.offsetByCodePoints(0, 100)) : cleaned);
            }
        }
        return List.copyOf(names);
    }

    private String normalize(String value) { return SPACE.matcher(value).replaceAll(" ").strip(); }
    private RawCell cell(RawRow row, int column) {
        return column < row.cells().size() ? row.cells().get(column) : new RawCell("", false);
    }
    private Problem problem(MonthSheet sheet, RawRow row, Level level, Code code) {
        return new Problem(sheet.sheet(), row.number(), level, code);
    }
}
