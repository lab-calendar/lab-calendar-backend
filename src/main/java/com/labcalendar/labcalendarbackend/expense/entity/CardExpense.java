package com.labcalendar.labcalendarbackend.expense.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Persistence mapping for the KAN-27 schema. Domain operations are added by feature tickets. */
@Entity
@Table(name = "card_expense")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CardExpense {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "source_document_id", nullable = false, length = 191)
    private String sourceDocumentId;

    @Column(name = "source_record_id", nullable = false, length = 191)
    private String sourceRecordId;

    @Column(name = "used_on", nullable = false)
    private LocalDate usedOn;

    @Column(name = "card_name", nullable = false, length = 100)
    private String cardName;

    @Column(name = "purpose", nullable = false, columnDefinition = "TEXT")
    private String purpose;

    @Column(name = "usage_type", nullable = false, length = 50)
    private String usageType;

    @Column(name = "participant_names_raw", columnDefinition = "TEXT")
    private String participantNamesRaw;

    @Column(name = "active", nullable = false, columnDefinition = "BOOLEAN")
    private Boolean active = true;

    @Column(name = "last_seen_at", nullable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime lastSeenAt;

    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now(java.time.Clock.systemUTC());
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now(java.time.Clock.systemUTC());
    }
}
