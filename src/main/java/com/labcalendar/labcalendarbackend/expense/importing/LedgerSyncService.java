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

    // Package-only gateway for KAN-59: verification and apply share the reconciliation lock.
    <T> T withImportLock(java.util.function.Supplier<T> work) { return locked(work); }

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
        return applyChecked(fileName, months, () -> {});
    }

    Result applyVerified(String fileName, List<ParsedMonth> months, java.util.function.Consumer<String> verify) {
        return applyChecked(fileName, months, () -> verify.accept(fingerprint(months)));
    }

    private Result applyChecked(String fileName, List<ParsedMonth> months, Runnable verify) {
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
                    log(label, started, result.partial() ? "PARTIAL" : "SUCCESS", result, months);
                    return result;
                }); // Commit/rollback completes before the lock is released.
            } catch (ImportApiException rejectedPreview) {
                // A rejected preview is not an attempted import: no business writes or failure log.
                throw rejectedPreview;
            } catch (RuntimeException failure) {
                try {
                    transaction.executeWithoutResult(status -> log(label, started, "FAILED", null, months));
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

    private void log(String name, LocalDateTime started, String status, Result result, List<ParsedMonth> months) {
        LocalDateTime finished = now();
        int added = result == null ? 0 : result.added();
        int same = result == null ? 0 : result.unchanged();
        int removed = result == null ? 0 : result.removed();
        long skipped = months.stream().mapToLong(ParsedMonth::errorRowCount).sum();
        long id = insert("""
                INSERT INTO sync_log (source_document_id, trigger_type, status, started_at, finished_at,
                duration_ms, processed_count, created_count, updated_count, skipped_count, deactivated_count, error_message)
                VALUES (?, 'MANUAL', ?, ?, ?, ?, ?, ?, 0, ?, ?, ?)
                """, name, status, started, finished, Math.max(0, java.time.Duration.between(started, finished).toMillis()),
                added + same, added, skipped, removed, result == null ? "IMPORT_FAILED" : null);
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
