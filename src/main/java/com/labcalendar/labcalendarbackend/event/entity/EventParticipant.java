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
@Table(name = "event_participant")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventParticipant {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(name = "position", nullable = false)
    private Integer position;

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
     * An attendee entered as free text. No member link yet — names alone cannot tell two people
     * with the same name apart, so that waits for the member picker (KAN-41).
     */
    public static EventParticipant unlinked(Long eventId, String displayName, int position) {
        EventParticipant participant = new EventParticipant();
        participant.eventId = eventId;
        participant.displayName = displayName;
        participant.position = position;
        return participant;
    }
}
