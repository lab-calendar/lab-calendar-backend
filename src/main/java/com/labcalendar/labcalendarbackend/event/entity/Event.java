package com.labcalendar.labcalendarbackend.event.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Persistence mapping for the KAN-27 schema. Domain operations are added by feature tickets. */
@Entity
@Table(name = "event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Event {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(name = "owner_member_id")
    private Long ownerMemberId;

    @Column(name = "research_project_id")
    private Long researchProjectId;

    @Column(name = "card_expense_id")
    private Long cardExpenseId;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "memo", columnDefinition = "TEXT")
    private String memo;

    @Column(name = "manual_detail", columnDefinition = "TEXT")
    private String manualDetail;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "all_day", nullable = false, columnDefinition = "BOOLEAN")
    private Boolean allDay = true;

    @Column(name = "start_time", columnDefinition = "TIME(0)")
    private LocalTime startTime;

    @Column(name = "end_time", columnDefinition = "TIME(0)")
    private LocalTime endTime;

    @Column(name = "source", nullable = false, length = 20)
    private String source;

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
