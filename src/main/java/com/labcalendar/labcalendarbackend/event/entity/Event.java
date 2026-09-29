package com.labcalendar.labcalendarbackend.event.entity;

import com.labcalendar.labcalendarbackend.event.EventSource;
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

    /**
     * Creates a manual event. Generated events are written by the lead-time batch (KAN-49) and the
     * Google sync (KAN-58), which own their source rows and set the matching foreign key.
     */
    public static Event manual(Long categoryId, String title, String detail, String memo,
            LocalDate startDate, LocalDate endDate) {
        Event event = new Event();
        event.categoryId = categoryId;
        event.title = title;
        event.manualDetail = detail;
        event.memo = memo;
        event.startDate = startDate;
        event.endDate = endDate;
        event.allDay = true;
        event.source = EventSource.MANUAL.name();
        return event;
    }

    /**
     * Creates the preparation schedule a project implies (KAN-49).
     *
     * <p>The project id is what makes this row unique: {@code uq_event_project} allows one per
     * project, so a repeated batch run cannot double it up.
     */
    public static Event generated(Long categoryId, Long researchProjectId, String title,
            LocalDate startDate, LocalDate endDate) {
        Event event = new Event();
        event.categoryId = categoryId;
        event.researchProjectId = researchProjectId;
        event.title = title;
        event.startDate = startDate;
        event.endDate = endDate;
        event.allDay = true;
        event.source = EventSource.AUTO_GENERATED.name();
        return event;
    }

    /**
     * Moves a generated schedule onto new dates.
     *
     * <p>Only the period and the stored title change. The category and the project it belongs to
     * are what identify the row, and a generated event carries no memo or participants.
     */
    public void applyGeneratedPeriod(String title, LocalDate startDate, LocalDate endDate) {
        this.title = title;
        this.startDate = startDate;
        this.endDate = endDate;
    }

    /** Applies an edit. Reachable only for manual events — the service rejects the rest. */
    public void applyManualEdit(Long categoryId, String title, String detail, String memo,
            LocalDate startDate, LocalDate endDate) {
        this.categoryId = categoryId;
        this.title = title;
        this.manualDetail = detail;
        this.memo = memo;
        this.startDate = startDate;
        this.endDate = endDate;
    }

    public EventSource source() {
        return EventSource.of(source);
    }
}
