package com.labcalendar.labcalendarbackend.expense.importing;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.labcalendar.labcalendarbackend.auth.CurrentSession;
import com.labcalendar.labcalendarbackend.common.exception.BusinessException;
import com.labcalendar.labcalendarbackend.common.exception.ErrorCode;

@Service
public class ImportHistoryService {
    private final JdbcTemplate jdbc;
    private final CurrentSession session;

    public ImportHistoryService(JdbcTemplate jdbc, CurrentSession session) {
        this.jdbc = jdbc;
        this.session = session;
    }

    public record Problem(String locator, String code, String level) {}
    public record History(String id, String fileName, String status, LocalDateTime startedAt,
            LocalDateTime finishedAt, Long durationMs, int processed, int added, int updated,
            int removed, int skippedRows, String errorCode, long problemCount, List<Problem> problems) {}

    @Transactional(readOnly = true)
    public List<History> list(int limit) {
        if (!session.seesCardData()) throw new BusinessException(ErrorCode.FORBIDDEN);
        if (limit < 1 || limit > 100) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        var histories = jdbc.query("""
                SELECT * FROM sync_log WHERE trigger_type='MANUAL'
                ORDER BY started_at DESC, id DESC LIMIT ?
                """, (rs, row) -> new History(rs.getString("id"), rs.getString("source_document_id"),
                rs.getString("status"), rs.getObject("started_at", LocalDateTime.class),
                rs.getObject("finished_at", LocalDateTime.class), rs.getObject("duration_ms", Long.class),
                rs.getInt("processed_count"), rs.getInt("created_count"), rs.getInt("updated_count"),
                rs.getInt("deactivated_count"), rs.getInt("skipped_count"),
                "FAILED".equals(rs.getString("status")) ? "IMPORT_FAILED" : null, 0, List.of()), limit);
        if (histories.isEmpty()) return histories;
        // Two queries regardless of history count; cap each problem list to keep responses bounded.
        String placeholders = String.join(",", java.util.Collections.nCopies(histories.size(), "?"));
        Map<String, List<Problem>> problems = new HashMap<>();
        Map<String, Long> counts = new HashMap<>();
        jdbc.query("""
                SELECT * FROM (
                    SELECT sync_log_id, source_locator, error_code, message,
                        ROW_NUMBER() OVER (PARTITION BY sync_log_id ORDER BY id) AS row_number_in_log,
                        COUNT(*) OVER (PARTITION BY sync_log_id) AS problem_count
                    FROM sync_log_error WHERE sync_log_id IN (%s)
                ) ranked WHERE row_number_in_log <= 100 ORDER BY sync_log_id, row_number_in_log
                """.formatted(placeholders), (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            String id = rs.getString("sync_log_id");
            String code = rs.getString("error_code");
            try { LedgerRowParser.Code.valueOf(code); }
            catch (IllegalArgumentException ignored) { code = "UNKNOWN"; }
            String level = "WARNING".equals(rs.getString("message")) ? "WARNING" : "ERROR";
            problems.computeIfAbsent(id, key -> new ArrayList<>())
                    .add(new Problem(rs.getString("source_locator"), code, level));
            counts.put(id, rs.getLong("problem_count"));
        }, histories.stream().map(h -> Long.valueOf(h.id())).toArray());
        return histories.stream().map(h -> new History(h.id(), h.fileName(), h.status(), h.startedAt(),
                h.finishedAt(), h.durationMs(), h.processed(), h.added(), h.updated(), h.removed(),
                h.skippedRows(), h.errorCode(), counts.getOrDefault(h.id(), 0L),
                List.copyOf(problems.getOrDefault(h.id(), List.of())))).toList();
    }
}
