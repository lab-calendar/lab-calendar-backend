package com.labcalendar.labcalendarbackend.event.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.labcalendar.labcalendarbackend.event.entity.Event;

public interface EventRepository extends JpaRepository<Event, Long> {

    /**
     * Events overlapping the closed range {@code [from, to]}.
     *
     * <p>Overlap rather than containment: a schedule that started last month and runs into this
     * one belongs on this month's calendar. Both ends are inclusive, matching how the dates are
     * stored and what the API promises — nothing here adds or subtracts a day.
     *
     * <p>The predicate is served by {@code ix_event_dates (start_date, end_date)}.
     */
    @Query("""
            SELECT e FROM Event e
            WHERE e.startDate <= :to AND e.endDate >= :from
            ORDER BY e.startDate ASC, e.id ASC
            """)
    List<Event> findOverlapping(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Same range rule, narrowed to categories. Served by {@code ix_event_category_dates}. */
    @Query("""
            SELECT e FROM Event e
            WHERE e.startDate <= :to AND e.endDate >= :from AND e.categoryId IN :categoryIds
            ORDER BY e.startDate ASC, e.id ASC
            """)
    List<Event> findOverlappingInCategories(@Param("from") LocalDate from, @Param("to") LocalDate to,
            @Param("categoryIds") Collection<Long> categoryIds);
}
