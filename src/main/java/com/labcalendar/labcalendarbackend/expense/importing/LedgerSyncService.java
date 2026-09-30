package com.labcalendar.labcalendarbackend.expense.importing;

import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.labcalendar.labcalendarbackend.expense.importing.LedgerRowParser.*;

/** Internal single-JVM reconciliation. Upload authorization and signed previews belong to KAN-59. */
@Service
public class LedgerSyncService {
    /** The two values {@code ck_sync_trigger} allows. */
    public static final String MANUAL = "MANUAL";
    public static final String SCHEDULED = "SCHEDULED";

    private static final ReentrantLock LOCK = new ReentrantLock(true);
    private static final tools.jackson.databind.json.JsonMapper MAPPER = new tools.jackson.databind.json.JsonMapper();
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readTransaction;

    public record MonthResult(YearMonth month, Status status, int added, int removed, int unchanged) {}
    public record Result(List<MonthResult> months) {
        public Result { months = List.copyOf(months); }
        public int added() { return months.stream().mapToInt(MonthResult::added).sum(); }
        public int removed() { return months.stream().mapToInt(MonthResult::removed).sum(); }
        public int unchanged() { return months.stream().mapToInt(MonthResult::unchanged).sum(); }
        public boolean partial() { return months.stream().anyMatch(m -> m.status() == Status.BLOCKED); }
    }
    private record Stored(long id, String key, boolean active) {}

    /**
     * Runs work with the reconciliation lock held (KAN-59, widened for KAN-88).
     *
     * <p>Verification and apply have to see the same rows, and so do the scheduled sync's measure
     * and apply steps. The lock is reentrant, so the {@code preview} and {@code apply} calls made
     * inside take it again without deadlocking.
     */
    public <T> T withImportLock(java.util.function.Supplier<T> work) { return locked(work); }

    /** Whether another import or sync holds the lock right now. */
    public boolean busy() { return LOCK.isLocked(); }

    record PreviewState(Result result, String fingerprint) {}
    PreviewState previewState(List<ParsedMonth> months) {
        validate(months);
        return locked(() -> readTransaction.execute(status -> new PreviewState(reconcile(months, false), fingerprint(months))));
    }

    String fingerprint(List<ParsedMonth> months) {
        var state = new ArrayList<Object>();
        for (var month : months.stream().map(ParsedMonth::month).sorted().toList()) {
            String document = "ledger:" + month;
            state.add(document);
            state.add(jdbc.queryForList("""
                    SELECT id, source_document_id, source_record_id, used_on, card_name, purpose,
                    usage_type, participant_names_raw, active FROM card_expense WHERE source_document_id=? ORDER BY id
                    """, document));
            state.add(jdbc.queryForList("""
                    SELECT e.id, e.category_id, e.owner_member_id, e.research_project_id, e.card_expense_id,
                    e.title, e.memo, e.manual_detail, e.start_date, e.end_date, e.all_day, e.start_time, e.end_time, e.source
                    FROM event e JOIN card_expense c ON c.id=e.card_expense_id WHERE c.source_document_id=? ORDER BY e.id
                    """, document));
            state.add(jdbc.queryForList("""
                    SELECT p.id, p.event_id, p.member_id, p.display_name, p.position FROM event_participant p
                    JOIN event e ON e.id=p.event_id JOIN card_expense c ON c.id=e.card_expense_id
                    WHERE c.source_document_id=? ORDER BY p.id
                    """, document));
        }
        // Include nulls and field boundaries; omit audit timestamps so unchanged replays remain valid.
        return PreviewTokenCodec.hash(MAPPER.writeValueAsBytes(state));
    }

    public LedgerSyncService(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.readTransaction = new TransactionTemplate(manager);
        this.readTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTransaction.setReadOnly(true);
        this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public Result preview(List<ParsedMonth> months) {
        validate(months);
        return locked(() -> readTransaction.execute(status -> reconcile(months, false)));
    }

    public Result apply(String fileName, List<ParsedMonth> months) {
        return applyChecked(fileName, months, MANUAL, () -> {}, result -> true);
    }

    /**
     * Applies on behalf of something other than an upload (KAN-88).
     *
     * <p>Only the label and the trigger recorded in the history differ. The reconciliation itself
     * must not know where the rows came from: a month uploaded yesterday and read from the sheet
     * today has to land on the same stored rows, not a second copy of them.
     */
    public Result apply(String label, List<ParsedMonth> months, String triggerType) {
        return apply(label, months, triggerType, result -> true);
    }

    /**
     * Applies, and decides per run whether the history is worth a row (KAN-91).
     *
     * <p>A timer that runs every few minutes writes one line per run, and almost all of them say
     * "nothing changed". The history screen is where the lab looks to find out what the last
     * import did; drowning it in no-ops answers that question worse, not better. So the caller
     * that knows how often it runs decides what deserves a line.
     *
     * <p>Failures are not subject to this — they are logged whatever the predicate says.
     */
    public Result apply(String label, List<ParsedMonth> months, String triggerType,
            java.util.function.Predicate<Result> worthLogging) {
        return applyChecked(label, months, triggerType, () -> {}, worthLogging);
    }

    /**
     * Records a run that never got as far as reconciling (KAN-89).
     *
     * <p>An unattended sync that fails silently looks exactly like one that had nothing to do. The
     * reason goes where the lab already looks for import history.
     */
    public void logFailure(String label, String triggerType, String reason, LocalDateTime started) {
        transaction.executeWithoutResult(status ->
                log(label, started, "FAILED", null, List.of(), triggerType, reason));
    }

    Result applyVerified(String fileName, List<ParsedMonth> months, java.util.function.Consumer<String> verify) {
        return applyChecked(fileName, months, MANUAL, () -> verify.accept(fingerprint(months)), result -> true);
    }

    private Result applyChecked(String fileName, List<ParsedMonth> months, String triggerType,
            Runnable verify, java.util.function.Predicate<Result> worthLogging) {
        validate(months);
        if (months.stream().noneMatch(m -> m.status() == Status.READY)) {
            throw new LedgerSyncException(LedgerSyncException.Code.NO_APPLICABLE_MONTHS);
        }
        String safeName = fileName == null ? "upload.xlsx" : fileName.replace('\\', '/');
        safeName = safeName.substring(safeName.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "_");
        if (safeName.isBlank()) safeName = "upload.xlsx";
        if (safeName.codePointCount(0, safeName.length()) > 191) {
            safeName = safeName.substring(0, safeName.offsetByCodePoints(0, 191));
        }
        final String label = safeName;
        return locked(() -> {
            LocalDateTime started = now();
            try {
                return transaction.execute(status -> {
                    verify.run();
                    Result result = reconcile(months, true);
                    if (worthLogging.test(result)) {
                        log(label, started, result.partial() ? "PARTIAL" : "SUCCESS", result, months,
                                triggerType, null);
                    }
                    return result;
                }); // Commit/rollback completes before the lock is released.
            } catch (ImportApiException rejectedPreview) {
                // A rejected preview is not an attempted import: no business writes or failure log.
                throw rejectedPreview;
            } catch (RuntimeException failure) {
                try {
                    transaction.executeWithoutResult(status ->
                            log(label, started, "FAILED", null, months, triggerType, null));
                } catch (RuntimeException logFailure) {
                    throw new LedgerSyncException(LedgerSyncException.Code.IMPORT_FAILED_LOG_UNAVAILABLE);
                }
                // JDBC exception messages can contain cell values. Never forward their cause.
                throw new LedgerSyncException(LedgerSyncException.Code.IMPORT_FAILED);
            }
        });
    }

    private <T> T locked(java.util.function.Supplier<T> work) {
        // Reject outer transactions: suspension could expose stale JPA state or hold DB locks.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new LedgerSyncException(LedgerSyncException.Code.OUTER_TRANSACTION_NOT_SUPPORTED);
        }
        LOCK.lock();
        try { return work.get(); } finally { LOCK.unlock(); }
    }

    private void validate(List<ParsedMonth> months) {
        if (months == null || months.isEmpty()) throw new LedgerSyncException(LedgerSyncException.Code.NO_MONTHS);
        Set<YearMonth> seen = new HashSet<>();
        for (var month : months) {
            if (!seen.add(month.month())) throw new LedgerSyncException(LedgerSyncException.Code.DUPLICATE_MONTH);
            if (month.month().getYear() < 1000 || month.month().getYear() > 9999) {
                throw new LedgerSyncException(LedgerSyncException.Code.UNSUPPORTED_DATABASE_YEAR);
            }
            for (var entry : month.entries()) {
                if (!YearMonth.from(entry.usedOn()).equals(month.month())) {
                    throw new LedgerSyncException(LedgerSyncException.Code.ROW_MONTH_MISMATCH);
                }
            }
        }
    }

    private Result reconcile(List<ParsedMonth> months, boolean write) {
        var results = new ArrayList<MonthResult>();
        Long category = write ? jdbc.queryForObject("SELECT id FROM category WHERE code = 'card'", Long.class) : null;
        for (var month : months) {
            if (month.status() == Status.BLOCKED) {
                results.add(new MonthResult(month.month(), Status.BLOCKED, 0, 0, 0));
                continue;
            }
            String document = "ledger:" + month.month();
            var stored = jdbc.query("SELECT id, source_record_id, active FROM card_expense WHERE source_document_id = ?",
                    (rs, n) -> new Stored(rs.getLong(1), rs.getString(2), rs.getBoolean(3)), document);
            var byKey = new HashMap<String, Stored>();
            stored.forEach(row -> byKey.put(row.key(), row));
            var occurrences = new HashMap<String, Integer>();
            Set<String> wanted = new HashSet<>();
            int added = 0, removed = 0, unchanged = 0;
            for (var entry : month.applicableEntries()) {
                String hash = LedgerRecordKey.digest(entry);
                String key = hash + "#" + occurrences.merge(hash, 1, Integer::sum);
                wanted.add(key);
                Stored existing = byKey.get(key);
                if (existing != null && existing.active()) {
                    unchanged++;
                    if (write) jdbc.update("UPDATE card_expense SET last_seen_at = ? WHERE id = ?", now(), existing.id());
                    continue;
                }
                added++; // Reappearance counts as added, but reuses the expense identity.
                if (write) {
                    LocalDateTime time = now();
                    long expenseId;
                    if (existing == null) {
                        expenseId = insert("""
                                INSERT INTO card_expense (source_document_id, source_record_id, used_on, card_name,
                                purpose, usage_type, participant_names_raw, active, last_seen_at, created_at, updated_at)
                                VALUES (?, ?, ?, ?, ?, ?, ?, TRUE, ?, ?, ?)
                                """, document, key, entry.usedOn(), entry.cardName(), entry.purpose(),
                                entry.usageType().name(), entry.participantNamesRaw(), time, time, time);
                    } else {
                        expenseId = existing.id();
                        jdbc.update("UPDATE card_expense SET active = TRUE, last_seen_at = ?, updated_at = ? WHERE id = ?",
                                time, time, expenseId);
                    }
                    long event = insert("""
                            INSERT INTO event (category_id, card_expense_id, title, memo, start_date, end_date,
                            all_day, source, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, TRUE, 'GOOGLE_SYNC', ?, ?)
                            """, category, expenseId, entry.cardName(), entry.memo(), entry.usedOn(), entry.usedOn(), time, time);
                    for (int position = 0; position < entry.participants().size(); position++) {
                        jdbc.update("""
                                INSERT INTO event_participant (event_id, display_name, position, created_at, updated_at)
                                VALUES (?, ?, ?, ?, ?)
                                """, event, entry.participants().get(position), position, time, time);
                    }
                }
            }
            for (var old : stored) {
                if (old.active() && !wanted.contains(old.key())) {
                    removed++;
                    if (write) {
                        // FK cascade removes event_participant; expense history remains.
                        jdbc.update("DELETE FROM event WHERE card_expense_id = ?", old.id());
                        jdbc.update("UPDATE card_expense SET active = FALSE, updated_at = ? WHERE id = ?", now(), old.id());
                    }
                }
            }
            results.add(new MonthResult(month.month(), Status.READY, added, removed, unchanged));
        }
        return new Result(results);
    }

    private void log(String name, LocalDateTime started, String status, Result result,
            List<ParsedMonth> months, String triggerType, String reason) {
        LocalDateTime finished = now();
        int added = result == null ? 0 : result.added();
        int same = result == null ? 0 : result.unchanged();
        int removed = result == null ? 0 : result.removed();
        long skipped = months.stream().mapToLong(ParsedMonth::errorRowCount).sum();
        String trigger = SCHEDULED.equals(triggerType) ? SCHEDULED : MANUAL;
        long id = insert("""
                INSERT INTO sync_log (source_document_id, trigger_type, status, started_at, finished_at,
                duration_ms, processed_count, created_count, updated_count, skipped_count, deactivated_count, error_message)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?)
                """, name, trigger, status, started, finished,
                Math.max(0, java.time.Duration.between(started, finished).toMillis()),
                added + same, added, skipped, removed,
                result != null ? null : reason != null ? reason : "IMPORT_FAILED");
        for (var month : months) for (var problem : month.problems()) {
            jdbc.update("""
                    INSERT INTO sync_log_error (sync_log_id, source_locator, error_code, message)
                    VALUES (?, ?, ?, ?)
                    """, id, month.sheet() + ":" + problem.row(), problem.code().name(), problem.level().name());
        }
    }

    private long insert(String sql, Object... args) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
}
