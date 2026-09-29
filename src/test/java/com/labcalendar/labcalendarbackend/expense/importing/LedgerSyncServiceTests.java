package com.labcalendar.labcalendarbackend.expense.importing;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import com.labcalendar.labcalendarbackend.auth.*;
import com.labcalendar.labcalendarbackend.auth.token.AuthTokenCodec;
import jakarta.servlet.http.Cookie;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;
import static com.labcalendar.labcalendarbackend.expense.importing.LedgerRowParser.*;

@SpringBootTest(properties = "spring.datasource.url=${MIGRATION_TEST_URL:jdbc:h2:mem:kan58;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}")
@AutoConfigureMockMvc
class LedgerSyncServiceTests {
    @Autowired LedgerSyncService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired MockMvc mvc;
    @Autowired AuthTokenCodec tokens;

    @Test
    void importedScheduleIsVisibleToEditorReadOnlyAndHiddenFromViewer() throws Exception {
        var entry = new Entry(2, LocalDate.of(2026, 9, 1), "가상 과제", "", UsageType.NONE,
                "홍길동(가상)", List.of("홍길동"), "홍길동(가상)");
        service.apply("sample.xlsx", List.of(new ParsedMonth("2026년 9월", YearMonth.of(2026, 9), List.of(entry), List.of())));
        long id = jdbc.queryForObject("SELECT id FROM event", Long.class);
        var editor = new Cookie(AuthCookie.NAME, tokens.issue(AuthTier.EDITOR).value());
        var viewer = new Cookie(AuthCookie.NAME, tokens.issue(AuthTier.VIEWER).value());
        mvc.perform(get("/api/events/" + id).cookie(editor)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("가상 과제"))
                .andExpect(jsonPath("$.data.detail").isEmpty())
                .andExpect(jsonPath("$.data.participants[0]").value("홍길동"))
                .andExpect(jsonPath("$.data.memo").value("홍길동(가상)"))
                .andExpect(jsonPath("$.data.source").value("GOOGLE_SYNC"));
        mvc.perform(delete("/api/events/" + id).cookie(editor)).andExpect(status().isForbidden());
        mvc.perform(get("/api/events?from=2026-09-01&to=2026-09-30").cookie(viewer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(get("/api/events/" + id).cookie(viewer)).andExpect(status().isNotFound());
    }

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM sync_log_error");
        jdbc.update("DELETE FROM sync_log");
        jdbc.update("DELETE FROM event_participant");
        jdbc.update("DELETE FROM event");
        jdbc.update("DELETE FROM card_expense");
    }

    @Test
    void preparedTwelveScenariosDriveRealDatabaseReconciliation() throws Exception {
        var root = JsonMapper.builder().build().readTree(getClass().getResourceAsStream("/expense/importing/month-sync-cases.json"));
        for (var scenario : root.get("scenarios")) {
            clean();
            String name = scenario.get("id").asText();
            var before = scenario.get("beforeActiveByMonth");
            for (String month : before.propertyNames()) {
                service.apply("seed.xlsx", List.of(fromKeys(month, before.get(month), false)));
            }
            var oldIds = jdbc.queryForList("SELECT id, card_expense_id FROM event ORDER BY id");
            var input = new ArrayList<ParsedMonth>();
            for (String month : scenario.get("input").propertyNames()) {
                var data = scenario.get("input").get(month);
                input.add(fromKeys(month, data.get("records"), data.get("status").asText().equals("BLOCKED")));
            }
            var preview = service.preview(input);
            var counts = scenario.get("expected").get("counts");
            assertThat(preview.added()).as(name).isEqualTo(counts.get("added").asInt());
            assertThat(preview.removed()).as(name).isEqualTo(counts.get("removed").asInt());
            assertThat(preview.unchanged()).as(name).isEqualTo(counts.get("unchanged").asInt());
            if (input.stream().allMatch(m -> m.status() == Status.BLOCKED)) {
                assertThatThrownBy(() -> service.apply("file.xlsx", input)).hasMessage("NO_APPLICABLE_MONTHS");
            } else {
                assertThat(service.apply("file.xlsx", input)).as(name).isEqualTo(preview);
            }
            var expected = scenario.get("expected").get("afterActiveByMonth");
            for (String month : expected.propertyNames()) {
                var wanted = keys(fromKeys(month, expected.get(month), false));
                assertThat(jdbc.queryForList("SELECT source_record_id FROM card_expense WHERE source_document_id=? AND active=TRUE",
                        String.class, "ledger:" + month)).as(name).containsExactlyInAnyOrderElementsOf(wanted);
            }
            assertThat(count("event")).as(name).isEqualTo(jdbc.queryForObject("SELECT COUNT(*) FROM card_expense WHERE active=TRUE", Integer.class));
            // Surviving events retain their identity rather than being deleted/recreated.
            for (var old : oldIds) {
                long expense = ((Number) old.get("card_expense_id")).longValue();
                if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT active FROM card_expense WHERE id=?", Boolean.class, expense))) {
                    assertThat(jdbc.queryForObject("SELECT id FROM event WHERE card_expense_id=?", Long.class, expense))
                            .as(name).isEqualTo(((Number) old.get("id")).longValue());
                }
            }
        }
    }

    @Test
    void previewDoesNotWriteAndReuploadKeepsExpenseEventAndParticipantIds() {
        var input = List.of(month("2026-09", "A", "B"));
        assertThat(service.preview(input).added()).isEqualTo(2);
        assertThat(count("sync_log")).isZero();
        assertThat(count("card_expense")).isZero();
        service.apply("sample.xlsx", input);
        var snapshot = snapshot();
        service.preview(List.of(month("2026-09")));
        assertThat(snapshot()).isEqualTo(snapshot);
        var ids = jdbc.queryForList("SELECT id, event_id, display_name FROM event_participant ORDER BY id");
        assertThat(service.apply("sample.xlsx", input).unchanged()).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT id, event_id, display_name FROM event_participant ORDER BY id")).isEqualTo(ids);
        assertThat(count("card_expense")).isEqualTo(2);
    }

    @Test
    void deletionCascadesAndReappearanceReusesExpenseWithNewEvent() {
        service.apply("file.xlsx", List.of(month("2026-09", "A")));
        long expense = jdbc.queryForObject("SELECT id FROM card_expense", Long.class);
        long event = jdbc.queryForObject("SELECT id FROM event", Long.class);
        service.apply("file.xlsx", List.of(month("2026-09")));
        assertThat(count("event")).isZero();
        assertThat(count("event_participant")).isZero();
        assertThat(jdbc.queryForObject("SELECT active FROM card_expense", Boolean.class)).isFalse();
        assertThat(service.apply("file.xlsx", List.of(month("2026-09", "A"))).added()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT id FROM card_expense", Long.class)).isEqualTo(expense);
        assertThat(jdbc.queryForObject("SELECT id FROM event", Long.class)).isNotEqualTo(event);
        assertThat(count("event_participant")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT SUM(deactivated_count) FROM sync_log", Integer.class)).isEqualTo(1);
    }

    @Test
    void blockedMonthPreservesEveryColumnIncludingInactiveRowsAndOtherMonthApplies() {
        service.apply("file.xlsx", List.of(month("2026-09", "A", "B")));
        service.apply("file.xlsx", List.of(month("2026-09", "A")));
        var before = jdbc.queryForList("SELECT * FROM card_expense ORDER BY id");
        var events = jdbc.queryForList("SELECT * FROM event ORDER BY id");
        var participants = jdbc.queryForList("SELECT * FROM event_participant ORDER BY id");
        var blocked = new ParsedMonth("2026년 9월", YearMonth.of(2026, 9), List.of(),
                List.of(new Problem("2026년 9월", 7, Level.ERROR, Code.INVALID_DATE)));
        service.apply("file.xlsx", List.of(blocked, month("2026-08", "C")));
        assertThat(jdbc.queryForList("SELECT * FROM card_expense WHERE source_document_id='ledger:2026-09' ORDER BY id")).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM event WHERE start_date='2026-09-01' ORDER BY id")).isEqualTo(events);
        assertThat(jdbc.queryForList("SELECT * FROM event_participant WHERE event_id=? ORDER BY id", events.get(0).get("id"))).isEqualTo(participants);
        assertThat(jdbc.queryForObject("SELECT status FROM sync_log ORDER BY id DESC LIMIT 1", String.class)).isEqualTo("PARTIAL");
    }

    @Test
    void lateFailureRollsBackEarlierDeletionAndCreationButCommitsSafeFailureLog() {
        service.apply("file.xlsx", List.of(month("2026-09", "A")));
        var expenses = jdbc.queryForList("SELECT * FROM card_expense");
        var events = jdbc.queryForList("SELECT * FROM event");
        var participants = jdbc.queryForList("SELECT * FROM event_participant");
        jdbc.execute("ALTER TABLE event ADD CONSTRAINT test_failure CHECK (title <> 'FAIL')");
        var blocked = new ParsedMonth("2026년 7월", YearMonth.of(2026, 7), List.of(), List.of(
                new Problem("2026년 7월", 8, Level.ERROR, Code.INVALID_DATE),
                new Problem("2026년 7월", 8, Level.ERROR, Code.CARD_MISSING),
                new Problem("2026년 7월", 9, Level.WARNING, Code.PARTICIPANTS_EMPTY)));
        try {
            assertThatThrownBy(() -> service.apply("file.xlsx", List.of(blocked, month("2026-09", "B"), month("2026-08", "FAIL"))))
                    .hasMessage("IMPORT_FAILED").hasNoCause();
        } finally {
            String database = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>)
                    connection -> connection.getMetaData().getDatabaseProductName());
            jdbc.execute("ALTER TABLE event DROP " + ("MySQL".equals(database) ? "CHECK" : "CONSTRAINT") + " test_failure");
        }
        assertThat(jdbc.queryForList("SELECT * FROM card_expense")).isEqualTo(expenses);
        assertThat(jdbc.queryForList("SELECT * FROM event")).isEqualTo(events);
        assertThat(jdbc.queryForList("SELECT * FROM event_participant")).isEqualTo(participants);
        assertThat(jdbc.queryForObject("SELECT status FROM sync_log ORDER BY id DESC LIMIT 1", String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT error_message FROM sync_log ORDER BY id DESC LIMIT 1", String.class)).isEqualTo("IMPORT_FAILED");
        assertThat(jdbc.queryForObject("SELECT skipped_count FROM sync_log ORDER BY id DESC LIMIT 1", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT error_code FROM sync_log_error ORDER BY id", String.class))
                .containsExactly("INVALID_DATE", "CARD_MISSING", "PARTICIPANTS_EMPTY");
        assertThat(jdbc.queryForList("SELECT message FROM sync_log_error ORDER BY id", String.class))
                .containsExactly("ERROR", "ERROR", "WARNING");
    }

    @Test
    void previewUsesReadOnlyTransactionAndApplyLooksUpCategoryOnceForAllMonths() {
        var modes = new ArrayList<Boolean>();
        PlatformTransactionManager observingManager = new PlatformTransactionManager() {
            public org.springframework.transaction.TransactionStatus getTransaction(org.springframework.transaction.TransactionDefinition definition) {
                modes.add(definition.isReadOnly());
                return manager.getTransaction(definition);
            }
            public void commit(org.springframework.transaction.TransactionStatus status) { manager.commit(status); }
            public void rollback(org.springframework.transaction.TransactionStatus status) { manager.rollback(status); }
        };
        var observedJdbc = org.mockito.Mockito.spy(jdbc);
        var observed = new LedgerSyncService(observedJdbc, observingManager, Clock.systemUTC());
        var input = List.of(month("2026-09", "A", "B"), month("2026-08", "C"));
        observed.preview(input);
        assertThat(modes).containsExactly(true);
        org.mockito.Mockito.verify(observedJdbc, org.mockito.Mockito.never())
                .queryForObject("SELECT id FROM category WHERE code = 'card'", Long.class);
        observed.apply("file.xlsx", input);
        assertThat(modes).containsExactly(true, false);
        org.mockito.Mockito.verify(observedJdbc, org.mockito.Mockito.times(1))
                .queryForObject("SELECT id FROM category WHERE code = 'card'", Long.class);
        assertThat(count("event")).isEqualTo(3);
    }

    @Test
    void recordIdentityIgnoresRowNumberWhitespaceAndUsesUnambiguousJson() {
        Entry a = new Entry(2, LocalDate.of(2026, 9, 1), " A  B ", " 점심 ", UsageType.LUNCH, " 홍길동  김철수 ", List.of(), null);
        Entry b = new Entry(99, a.usedOn(), "A B", "점심", a.usageType(), "홍길동 김철수", List.of(), null);
        assertThat(LedgerRecordKey.digest(a)).isEqualTo(LedgerRecordKey.digest(b));
        Entry c = new Entry(2, a.usedOn(), "A|B", "점심", a.usageType(), "C", List.of(), null);
        Entry d = new Entry(2, a.usedOn(), "A", "점심", a.usageType(), "B|C", List.of(), null);
        assertThat(LedgerRecordKey.digest(c)).isNotEqualTo(LedgerRecordKey.digest(d));
    }

    @Test
    void rejectsOuterTransactionAndInvalidMonthBeforeWrites() {
        new TransactionTemplate(manager).executeWithoutResult(status ->
                assertThatThrownBy(() -> service.apply("file.xlsx", List.of(month("2026-09", "A"))))
                        .hasMessage("OUTER_TRANSACTION_NOT_SUPPORTED"));
        assertThatThrownBy(() -> service.apply("file.xlsx", List.of(month("2026-09"), month("2026-09"))))
                .hasMessage("DUPLICATE_MONTH");
        assertThat(count("sync_log")).isZero();
    }

    @Test
    void concurrentUploadsWaitThroughCommitWithoutDuplicateRows() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var clockCalls = new AtomicInteger();
        Clock blockingClock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() {
                // Third clock read is audit completion: inserts have run but not committed.
                if (clockCalls.incrementAndGet() == 3) {
                    entered.countDown();
                    try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(); }
                }
                return Instant.parse("2026-09-01T00:00:00Z");
            }
        };
        var controlled = new LedgerSyncService(jdbc, manager, blockingClock);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var one = pool.submit(() -> controlled.apply("file.xlsx", List.of(month("2026-09", "A"))));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var twoStarted = new CountDownLatch(1);
            var two = pool.submit(() -> { twoStarted.countDown(); return service.apply("file.xlsx", List.of(month("2026-09", "A"))); });
            assertThat(twoStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(two.isDone()).isFalse();
            release.countDown();
            assertThat(one.get(10, TimeUnit.SECONDS).added()).isEqualTo(1);
            assertThat(two.get(10, TimeUnit.SECONDS).unchanged()).isEqualTo(1);
            assertThat(count("card_expense")).isEqualTo(1);
            assertThat(count("event")).isEqualTo(1);
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    private List<Object> snapshot() {
        return List.of(jdbc.queryForList("SELECT * FROM card_expense ORDER BY id"), jdbc.queryForList("SELECT * FROM event ORDER BY id"),
                jdbc.queryForList("SELECT * FROM event_participant ORDER BY id"), jdbc.queryForList("SELECT * FROM sync_log ORDER BY id"));
    }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private ParsedMonth month(String month, String... keys) {
        var ym = YearMonth.parse(month);
        var entries = new ArrayList<Entry>();
        for (String key : keys) {
            String symbol = key.split("#")[0];
            entries.add(new Entry(entries.size() + 2, ym.atDay(1), symbol, "점심", UsageType.LUNCH,
                    symbol.equals("W") ? "" : "가상인물", symbol.equals("W") ? List.of() : List.of("가상인물"), null));
        }
        return new ParsedMonth(month, ym, entries, List.of());
    }
    private ParsedMonth fromKeys(String ym, tools.jackson.databind.JsonNode node, boolean blocked) {
        var names = new ArrayList<String>();
        node.forEach(n -> names.add(n.asText()));
        var m = month(ym, names.toArray(String[]::new));
        return blocked ? new ParsedMonth(ym, m.month(), m.entries(), List.of(new Problem(ym, 9, Level.ERROR, Code.INVALID_DATE))) : m;
    }
    private List<String> keys(ParsedMonth month) {
        var counts = new HashMap<String, Integer>();
        return month.entries().stream().map(e -> { String hash = LedgerRecordKey.digest(e); return hash + "#" + counts.merge(hash, 1, Integer::sum); }).toList();
    }
}
