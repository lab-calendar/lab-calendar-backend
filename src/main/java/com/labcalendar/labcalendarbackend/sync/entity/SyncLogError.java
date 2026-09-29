package com.labcalendar.labcalendarbackend.sync.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Persistence mapping for the KAN-27 schema. Domain operations are added by feature tickets. */
@Entity
@Table(name = "sync_log_error")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SyncLogError {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "sync_log_id", nullable = false)
    private Long syncLogId;

    @Column(name = "source_locator", nullable = false, length = 255)
    private String sourceLocator;

    @Column(name = "source_record_id", length = 191)
    private String sourceRecordId;

    @Column(name = "error_code", nullable = false, length = 50)
    private String errorCode;

    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    private String message;
}
