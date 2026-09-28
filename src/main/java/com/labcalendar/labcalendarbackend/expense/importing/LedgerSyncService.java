package com.labcalendar.labcalendarbackend.expense.importing;

import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.YearMonth;
import java.util.*;
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
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public record MonthResult(YearMonth month, Status status, int added, int removed, int unchanged) {}
    public record Result(List<MonthResult> months) {
        public Result { months = List.copyOf(months); }
        public int added() { return months.stream().mapToInt(MonthResult::added).sum(); }
        public int removed() { return months.stream().mapToInt(MonthResult::removed).sum(); }
        public int unchanged() { return months.stream().mapToInt(MonthResult::unchanged).sum(); }
        public boolean partial() { return months.stream().anyMatch(m -> m.status() == Status.BLOCKED); }
    }
    private record Stored(long id, String key, boolean active) {}

    public LedgerSyncService(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Result preview(List<ParsedMonth> months) {
        validate(months);
        return locked(() -> transaction.execute(status -> reconcile(months, false)));
    }

    public Result apply(String fileName, List<ParsedMonth> months) {
        validate(months);
        if (months.stream().noneMatch(m -> m.status() == Status.READY)) {
            throw new LedgerSyncException("NO_APPLICABLE_MONTHS");
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
                    Result result = reconcile(months, true);
                    log(label, started, result.partial() ? "PARTIAL" : "SUCCESS", result, months);
                    return result;
                }); // Commit/rollback completes before the lock is released.
            } catch (RuntimeException failure) {
                try {
                    transaction.executeWithoutResult(status -> log(label, started, "FAILED", null, List.of()));
                } catch (RuntimeException logFailure) {
                    throw new LedgerSyncException("IMPORT_FAILED_LOG_UNAVAILABLE");
                }
                // JDBC exception messages can contain cell values. Never forward their cause.
                throw new LedgerSyncException("IMPORT_FAILED");
            }
        });
    }

    private <T> T locked(java.util.function.Supplier<T> work) {
        // Reject outer transactions: suspension could expose stale JPA state or hold DB locks.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new LedgerSyncException("OUTER_TRANSACTION_NOT_SUPPORTED");
        }
        LOCK.lock();
        try { return work.get(); } finally { LOCK.unlock(); }
    }

    private void validate(List<ParsedMonth> months) {
        if (months == null || months.isEmpty()) throw new LedgerSyncException("NO_MONTHS");
        Set<YearMonth> seen = new HashSet<>();
        for (var month : months) {
            if (!seen.add(month.month())) throw new LedgerSyncException("DUPLICATE_MONTH");
            if (month.month().getYear() < 1000 || month.month().getYear() > 9999) {
                throw new LedgerSyncException("UNSUPPORTED_DATABASE_YEAR");
            }
            for (var entry : month.entries()) {
                if (!YearMonth.from(entry.usedOn()).equals(month.month())) {
                    throw new LedgerSyncException("ROW_MONTH_MISMATCH");
                }
            }
        }
    }

    private Result reconcile(List<ParsedMonth> months, boolean write) {
        var results = new ArrayList<MonthResult>();
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
                    long category = jdbc.queryForObject("SELECT id FROM category WHERE code = 'card'", Long.class);
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
