package com.labcalendar.labcalendarbackend.expense.sheets;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import com.labcalendar.labcalendarbackend.expense.importing.ExcelReadException;
import com.labcalendar.labcalendarbackend.expense.importing.LedgerRowParser;
import com.labcalendar.labcalendarbackend.expense.importing.LedgerRowParser.Level;
import com.labcalendar.labcalendarbackend.expense.importing.LedgerRowParser.ParsedMonth;
import com.labcalendar.labcalendarbackend.expense.importing.LedgerRowParser.Problem;
import com.labcalendar.labcalendarbackend.expense.importing.LedgerRowParser.Status;
import com.labcalendar.labcalendarbackend.expense.importing.LedgerSheetSelector;
import com.labcalendar.labcalendarbackend.expense.importing.LedgerSyncService;

/**
 * One run of the scheduled sheet synchronization (KAN-88, KAN-89).
 *
 * <p>Reads, measures, then applies. The measuring step is the difference between this and an
 * upload: a person uploading a file sees the preview and can decide that a month losing everything
 * is wrong. Nobody sees this one, so the brakes are here instead — a month that would lose more
 * than the settings allow is blocked, with the reason kept in the history.
 *
 * <p>Nothing in here decides what a row means. Selection, parsing and reconciliation are the same
 * code the upload path runs, because two implementations of "what the ledger says" would drift.
 */
public class SheetsSyncService {

    /** What the history row is labelled with, in place of an uploaded file name. */
    static final String LABEL = "구글 시트 자동 동기화";

    private final SheetsLedgerReader reader;
    private final LedgerSheetSelector selector;
    private final LedgerRowParser parser;
    private final LedgerSyncService sync;
    private final SheetsProperties properties;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public SheetsSyncService(SheetsLedgerReader reader, LedgerSheetSelector selector, LedgerRowParser parser,
            LedgerSyncService sync, SheetsProperties properties, JdbcTemplate jdbc, Clock clock) {
        this.reader = reader;
        this.selector = selector;
        this.parser = parser;
        this.sync = sync;
        this.properties = properties;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** A month the brake stopped, and the numbers that stopped it. */
    public record Braked(YearMonth month, int wouldRemove, long active) {}

    public record Outcome(boolean applied, String failure, List<LedgerSyncService.MonthResult> months,
            List<Braked> braked) {
        public Outcome {
            months = List.copyOf(months);
            braked = List.copyOf(braked);
        }
        public int added() { return months.stream().mapToInt(LedgerSyncService.MonthResult::added).sum(); }
        public int removed() { return months.stream().mapToInt(LedgerSyncService.MonthResult::removed).sum(); }
        public int unchanged() { return months.stream().mapToInt(LedgerSyncService.MonthResult::unchanged).sum(); }
    }

    /**
     * Reads the sheet and reconciles it.
     *
     * <p>Never throws for an outside failure — the reason is written to the history and returned,
     * because the caller is usually a timer that has nobody to raise it to.
     */
    public Outcome run(String triggerType) {
        LocalDateTime started = now();
        try {
            return sync.withImportLock(() -> apply(triggerType));
        } catch (SheetsApiException failure) {
            return failed(triggerType, "SHEETS_" + failure.code().name(), started);
        } catch (ExcelReadException malformed) {
            // The document is reachable but not shaped like a ledger. Nothing is touched.
            return failed(triggerType, "SHEET_" + malformed.code().name(), started);
        } catch (RuntimeException unexpected) {
            /*
             * Whatever this was, its message may carry cell values, and the history is read by the
             * whole lab. Record that the run failed, not what the exception said.
             */
            return failed(triggerType, "SYNC_FAILED", started);
        }
    }

    private Outcome apply(String triggerType) {
        var selection = selector.select(reader.read());
        List<ParsedMonth> months = parser.parse(selection);

        // Measure first: reconcile without writing, so the brake sees the same numbers apply would.
        LedgerSyncService.Result preview = sync.preview(months);
        var braked = new ArrayList<Braked>();
        List<ParsedMonth> guarded = new ArrayList<>(months.size());

        for (ParsedMonth month : months) {
            Braked brake = brakeFor(month, preview);
            if (brake == null) {
                guarded.add(month);
                continue;
            }
            braked.add(brake);
            guarded.add(blocked(month));
        }

        if (guarded.stream().noneMatch(month -> month.status() == Status.READY)) {
            /*
             * Every month is blocked — by bad rows, by the brake, or both. Reconciliation would
             * refuse this anyway; recording it as a failure says why rather than leaving an hour
             * with no trace.
             */
            return failed(triggerType, braked.isEmpty() ? "NO_APPLICABLE_MONTHS" : "REMOVAL_LIMIT", now());
        }

        LedgerSyncService.Result result = sync.apply(LABEL, guarded, triggerType);
        return new Outcome(true, null, result.months(), braked);
    }

    /**
     * Whether this month would lose more than an unattended run is allowed to remove.
     *
     * <p>Both limits have to be crossed. Count alone would stop a large month's ordinary edit; the
     * share alone would stop a four-row month losing three. Together they describe the thing worth
     * stopping — a month that was full and is suddenly nearly empty.
     */
    private Braked brakeFor(ParsedMonth month, LedgerSyncService.Result preview) {
        if (month.status() != Status.READY) return null;

        int wouldRemove = preview.months().stream()
                .filter(result -> result.month().equals(month.month()))
                .mapToInt(LedgerSyncService.MonthResult::removed)
                .findFirst().orElse(0);
        if (wouldRemove <= properties.getMaxRemovalsPerMonth()) return null;

        long active = activeRows(month.month());
        if (active <= 0) return null;
        if (wouldRemove < active * properties.getMaxRemovalRatio()) return null;

        return new Braked(month.month(), wouldRemove, active);
    }

    private long activeRows(YearMonth month) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM card_expense WHERE source_document_id = ? AND active = TRUE",
                Long.class, "ledger:" + month);
        return count == null ? 0 : count;
    }

    /**
     * Turns a braked month into a blocked one.
     *
     * <p>Blocking already means "leave this month exactly as it is, and say why" everywhere
     * downstream — reconciliation skips it, the run comes out PARTIAL, and the reason lands in
     * {@code sync_log_error}. Reusing it beats a second notion of "do not touch this".
     */
    private ParsedMonth blocked(ParsedMonth month) {
        var problems = new ArrayList<>(month.problems());
        problems.add(new Problem(month.sheet(), 0, Level.ERROR, LedgerRowParser.Code.REMOVAL_LIMIT));
        return new ParsedMonth(month.sheet(), month.month(), month.entries(), problems);
    }

    private Outcome failed(String triggerType, String reason, LocalDateTime started) {
        try {
            sync.logFailure(LABEL, triggerType, reason, started);
        } catch (RuntimeException unavailable) {
            // The database is where the reason was going. There is nowhere else to put it.
        }
        return new Outcome(false, reason, List.of(), List.of());
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
