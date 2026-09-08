package com.labcalendar.labcalendarbackend;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

@SpringBootTest
class SchemaMigrationTests {
    @Autowired Flyway flyway;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void startupMigratesAndRestartDoesNotRepeatSeedData() {
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("1.1.0.001");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForList("SELECT code FROM category ORDER BY sort_order", String.class))
                .containsExactly("project", "lab", "card");
        for (String table : new String[]{"member", "research_project", "card_expense", "event",
                "event_participant", "sync_log", "sync_log_error"}) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class)).isZero();
        }
    }

    @Test
    void nullableSourceKeysAllowManualEventsButInvalidDatesAndSourcesFail() {
        rollback(() -> {
            insertManual(101, "2026-08-30", "2026-09-02");
            insertManual(102, "2026-09-30", "2026-10-02");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event WHERE start_date <= '2026-09-30' AND end_date >= '2026-09-01'", Integer.class)).isEqualTo(2);
            assertCheckViolation(() -> insertManual(103, "2026-09-08", "2026-09-07"));
            assertCheckViolation(() -> jdbc.update("UPDATE event SET source='AUTO_GENERATED' WHERE id=101"));
            assertCheckViolation(() -> jdbc.update("UPDATE event SET all_day=FALSE WHERE id=101"));
        });
    }

    @Test
    void projectSourceIsUniqueAndCannotBeDeletedWhileReferenced() {
        rollback(() -> {
            jdbc.update("INSERT INTO research_project (id,name,submission_type,end_date,created_at,updated_at) VALUES (201,'BRL','annual','2027-04-30',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            assertThat(jdbc.queryForObject("SELECT lead_time_days FROM research_project WHERE id=201", Integer.class)).isEqualTo(21);
            insertManual(201, "2027-04-09", "2027-04-30");
            jdbc.update("UPDATE event SET source='AUTO_GENERATED',research_project_id=201 WHERE id=201");
            insertManual(202, "2027-04-09", "2027-04-30");
            assertThatThrownBy(() -> jdbc.update("UPDATE event SET source='AUTO_GENERATED',research_project_id=201 WHERE id=202"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("DELETE FROM research_project WHERE id=201"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertCheckViolation(() -> jdbc.update("UPDATE research_project SET lead_time_days=-1 WHERE id=201"));
        });
    }

    @Test
    void externalIdsAreCaseSensitiveAndUniqueWithinDocument() {
        rollback(() -> {
            insertCard(301, "doc", "RowA");
            insertCard(302, "doc", "rowa");
            insertCard(303, "other-doc", "RowA");
            assertThatThrownBy(() -> insertCard(304, "doc", "RowA"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            insertManual(301, "2026-09-08", "2026-09-08");
            jdbc.update("UPDATE event SET category_id=(SELECT id FROM category WHERE code='card'),source='GOOGLE_SYNC',card_expense_id=301 WHERE id=301");
            insertManual(302, "2026-09-08", "2026-09-08");
            assertThatThrownBy(() -> jdbc.update("UPDATE event SET source='GOOGLE_SYNC',card_expense_id=301 WHERE id=302"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        });
    }

    @Test
    void participantsPermitNamesakesButEnforceReferencesAndCascade() {
        rollback(() -> {
            jdbc.update("INSERT INTO member (id,name,created_at,updated_at) VALUES (401,'Kim',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),(402,'Kim',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            insertManual(401, "2026-09-08", "2026-09-08");
            insertParticipant(401, 401L, 0);
            insertParticipant(401, 402L, 1);
            insertParticipant(401, null, 2);
            insertParticipant(401, null, 3);
            assertThatThrownBy(() -> insertParticipant(401, 401L, 4)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> insertParticipant(401, 999L, 4)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("DELETE FROM member WHERE id=401")).isInstanceOf(DataIntegrityViolationException.class);
            jdbc.update("DELETE FROM event WHERE id=401");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_participant WHERE event_id=401", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member WHERE name='Kim'", Integer.class)).isEqualTo(2);
        });
    }

    @Test
    void logCountersAreValidatedAndErrorsCascade() {
        rollback(() -> {
            jdbc.update("INSERT INTO sync_log(id,source_document_id,trigger_type,status,started_at) VALUES(501,'doc','MANUAL','RUNNING',CURRENT_TIMESTAMP)");
            jdbc.update("INSERT INTO sync_log_error(sync_log_id,source_locator,error_code,message) VALUES(501,'row 1','BAD_DATE','Invalid date')");
            assertCheckViolation(() -> jdbc.update("UPDATE sync_log SET created_count=1 WHERE id=501"));
            assertCheckViolation(() -> jdbc.update("UPDATE sync_log SET duration_ms=-1 WHERE id=501"));
            jdbc.update("DELETE FROM sync_log WHERE id=501");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sync_log_error WHERE sync_log_id=501", Integer.class)).isZero();
        });
    }

    private void rollback(Runnable assertions) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            status.setRollbackOnly();
            assertions.run();
        });
    }

    private void assertCheckViolation(Runnable operation) {
        Throwable failure = catchThrowable(operation::run);
        assertThat(failure).isNotNull();
        while (failure.getCause() != null) failure = failure.getCause();
        assertThat(failure).isInstanceOf(java.sql.SQLException.class);
        java.sql.SQLException sql = (java.sql.SQLException) failure;
        // MySQL reports CHECK violations as HY000/3819; H2 uses 23513.
        assertThat("23513".equals(sql.getSQLState())
                || ("HY000".equals(sql.getSQLState()) && sql.getErrorCode() == 3819)).isTrue();
    }

    private void insertManual(long id, String start, String end) {
        jdbc.update("INSERT INTO event(id,category_id,title,start_date,end_date,source,created_at,updated_at) VALUES (?,(SELECT id FROM category WHERE code='project'),'Test',?,?,'MANUAL',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", id,start,end);
    }

    private void insertCard(long id, String document, String record) {
        jdbc.update("INSERT INTO card_expense(id,source_document_id,source_record_id,used_on,card_name,purpose,usage_type,last_seen_at,created_at,updated_at) VALUES(?,?,?,'2026-09-08','Card','Meeting','meeting',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", id,document,record);
    }

    private void insertParticipant(long event, Long member, int position) {
        jdbc.update("INSERT INTO event_participant(event_id,member_id,display_name,position,created_at,updated_at) VALUES(?,?,'Kim',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",event,member,position);
    }
}
