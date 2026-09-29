package com.labcalendar.labcalendarbackend.project.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.labcalendar.labcalendarbackend.category.entity.Category;
import com.labcalendar.labcalendarbackend.category.repository.CategoryRepository;
import com.labcalendar.labcalendarbackend.common.exception.BusinessException;
import com.labcalendar.labcalendarbackend.common.exception.ErrorCode;
import com.labcalendar.labcalendarbackend.event.entity.Event;
import com.labcalendar.labcalendarbackend.event.repository.EventRepository;
import com.labcalendar.labcalendarbackend.project.entity.PreparationEvent;
import com.labcalendar.labcalendarbackend.project.entity.ResearchProject;
import com.labcalendar.labcalendarbackend.project.repository.ResearchProjectRepository;

/**
 * Keeps each project's preparation schedule in step with the project (KAN-49).
 *
 * <p>기획서 3.1 — registering a project should be enough to see "[작성 요망] … 준비 시작" on the
 * calendar as a bar running from the lead-time date to the deadline. Nobody draws that by hand.
 *
 * <p>The work is a reconcile, not an append: for a given project there either is a generated
 * event or there is not, and if there is, its period either already says the right thing or gets
 * moved. Nothing here appends blindly, so running it twice changes nothing the second time. The
 * schema backs that up — {@code uq_event_project} allows one generated event per project, so a
 * duplicate would be rejected rather than silently stored.
 *
 * <p>Inactive projects keep their row. Hiding a project is how the lab takes a preparation period
 * off the calendar without deleting the project (docs/api-contract.md §7.3), and the range query
 * already leaves those rows out. Deleting and rewriting them on every toggle would churn the
 * table for a result the query gives for free — and would make a schedule briefly reappear if the
 * batch ran before the next deactivation reached it.
 */
@Service
public class PreparationScheduleService {

    /** Generated schedules belong to the project category (기획서 3.1). */
    private static final String PROJECT_CATEGORY_CODE = "project";

    private final EventRepository events;
    private final ResearchProjectRepository projects;
    private final CategoryRepository categories;

    public PreparationScheduleService(EventRepository events, ResearchProjectRepository projects,
            CategoryRepository categories) {
        this.events = events;
        this.projects = projects;
        this.categories = categories;
    }

    /**
     * Brings every project's schedule up to date.
     *
     * <p>Both sides are read once and matched in memory rather than queried per project: the batch
     * runs over the whole table, and a lab's project list is small enough to hold.
     */
    @Transactional
    public SyncResult syncAll() {
        List<ResearchProject> all = projects.findAll();
        Map<Long, Event> existingByProject = new HashMap<>();
        for (Event event : events.findByResearchProjectIdIsNotNull()) {
            existingByProject.put(event.getResearchProjectId(), event);
        }

        Long categoryId = projectCategoryId();
        int created = 0;
        int moved = 0;
        List<Event> newRows = new ArrayList<>();

        for (ResearchProject project : all) {
            PreparationEvent wanted = PreparationEvent.of(project);
            Event existing = existingByProject.get(project.getId());

            if (existing == null) {
                newRows.add(Event.generated(categoryId, project.getId(), wanted.title(),
                        wanted.startDate(), wanted.endDate()));
                created++;
            } else if (!wanted.matches(existing)) {
                existing.applyGeneratedPeriod(wanted.title(), wanted.startDate(), wanted.endDate());
                moved++;
            }
        }

        events.saveAll(newRows);
        return new SyncResult(created, moved, all.size());
    }

    /**
     * Brings one project's schedule up to date.
     *
     * <p>Called when a project is registered or edited so the calendar is right immediately.
     * Waiting for the nightly run would mean a project you just entered has no bar until tomorrow.
     */
    @Transactional
    public void sync(ResearchProject project) {
        PreparationEvent wanted = PreparationEvent.of(project);
        Optional<Event> existing = events.findByResearchProjectId(project.getId());

        if (existing.isPresent()) {
            Event event = existing.get();
            if (!wanted.matches(event)) {
                event.applyGeneratedPeriod(wanted.title(), wanted.startDate(), wanted.endDate());
            }
            return;
        }
        events.save(Event.generated(projectCategoryId(), project.getId(), wanted.title(),
                wanted.startDate(), wanted.endDate()));
    }

    /**
     * Drops the schedule a project owns, so the project itself can be deleted.
     *
     * <p>The generated event holds a RESTRICT foreign key onto the project. Since every project
     * now has one, deleting a project without clearing this first would always conflict.
     */
    @Transactional
    public void removeFor(Long projectId) {
        events.findByResearchProjectId(projectId).ifPresent(events::delete);
        // The delete has to reach the database before the project row goes, or the foreign key
        // trips on a row the persistence context has not flushed yet.
        events.flush();
    }

    private Long projectCategoryId() {
        return categories.findByCode(PROJECT_CATEGORY_CODE)
                .map(Category::getId)
                // Seeded by the initial migration. Missing means the database is not what we built.
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));
    }

    /** What one reconcile did, for the manual trigger's response and the batch log. */
    public record SyncResult(int created, int moved, int projects) {
    }
}
