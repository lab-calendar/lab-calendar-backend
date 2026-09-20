package com.labcalendar.labcalendarbackend.event.repository;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import com.labcalendar.labcalendarbackend.event.entity.EventParticipant;

public interface EventParticipantRepository extends JpaRepository<EventParticipant, Long> {

    /** Display order is the stored position, not insertion order (KAN-27 event_participant.position). */
    List<EventParticipant> findByEventIdOrderByPositionAsc(Long eventId);

    /** One query for a whole page of events, so listing does not fan out per event. */
    List<EventParticipant> findByEventIdInOrderByEventIdAscPositionAsc(Collection<Long> eventIds);

    void deleteByEventId(Long eventId);
}
