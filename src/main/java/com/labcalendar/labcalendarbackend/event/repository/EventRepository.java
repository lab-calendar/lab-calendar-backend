package com.labcalendar.labcalendarbackend.event.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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
     * <p>A generated schedule whose project has been switched off is left out. Hiding a project is
     * how the lab takes its preparation period off the calendar without deleting the project
     * (docs/api-contract.md §7.3), so the filter belongs in the query: waiting for the batch to
     * clear those rows would leave them showing until it next runs.
     *
     * <p>{@code includeCardData} is false for viewers. Withholding spending is the whole point of
     * that tier (KAN-35), so it is excluded here rather than after loading — what is never read
     * cannot be leaked by a later change. Both the category and the card link are checked: a
     * manually filed card entry has no link, and a synced one could in principle be recategorised.
     *
     * <p>The date predicate is served by {@code ix_event_dates (start_date, end_date)}.
     */
    @Query("""
            SELECT e FROM Event e
            WHERE e.startDate <= :to AND e.endDate >= :from
              AND (e.researchProjectId IS NULL
                   OR EXISTS (SELECT 1 FROM ResearchProject p
                              WHERE p.id = e.researchProjectId AND p.active = TRUE))
              AND (:includeCardData = TRUE
                   OR (e.cardExpenseId IS NULL
                       AND NOT EXISTS (SELECT 1 FROM Category c
                                       WHERE c.id = e.categoryId AND c.code = 'card')))
            ORDER BY e.startDate ASC, e.id ASC
            """)
    List<Event> findOverlapping(@Param("from") LocalDate from, @Param("to") LocalDate to,
            @Param("includeCardData") boolean includeCardData);

    /** Same range and visibility rules, narrowed to categories. Served by {@code ix_event_category_dates}. */
    @Query("""
            SELECT e FROM Event e
            WHERE e.startDate <= :to AND e.endDate >= :from AND e.categoryId IN :categoryIds
              AND (e.researchProjectId IS NULL
                   OR EXISTS (SELECT 1 FROM ResearchProject p
                              WHERE p.id = e.researchProjectId AND p.active = TRUE))
              AND (:includeCardData = TRUE
                   OR (e.cardExpenseId IS NULL
                       AND NOT EXISTS (SELECT 1 FROM Category c
                                       WHERE c.id = e.categoryId AND c.code = 'card')))
            ORDER BY e.startDate ASC, e.id ASC
            """)
    List<Event> findOverlappingInCategories(@Param("from") LocalDate from, @Param("to") LocalDate to,
            @Param("categoryIds") Collection<Long> categoryIds,
            @Param("includeCardData") boolean includeCardData);

    /**
     * The generated schedule belonging to a project, if the batch has written one (KAN-49).
     *
     * <p>At most one can exist: {@code uq_event_project} is a unique constraint on the column.
     *
     * <p>No tier filter here. This is the batch reconciling its own rows, not a calendar read —
     * a preparation schedule is never card data, and the batch is not acting for a viewer.
     */
    Optional<Event> findByResearchProjectId(Long researchProjectId);

    /** Every generated schedule there is, so the batch can reconcile them in one pass. */
    List<Event> findByResearchProjectIdIsNotNull();
}
