package com.labcalendar.labcalendarbackend.event.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.labcalendar.labcalendarbackend.event.entity.Event;

public interface EventRepository extends JpaRepository<Event, Long> {
}
