package com.labcalendar.labcalendarbackend.event.service;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.labcalendar.labcalendarbackend.category.entity.Category;
import com.labcalendar.labcalendarbackend.category.repository.CategoryRepository;
import com.labcalendar.labcalendarbackend.common.exception.BusinessException;
import com.labcalendar.labcalendarbackend.common.exception.ErrorCode;
import com.labcalendar.labcalendarbackend.event.EventSource;
import com.labcalendar.labcalendarbackend.event.dto.EventRequest;
import com.labcalendar.labcalendarbackend.event.dto.EventResponse;
import com.labcalendar.labcalendarbackend.event.entity.Event;
import com.labcalendar.labcalendarbackend.event.entity.EventParticipant;
import com.labcalendar.labcalendarbackend.event.repository.EventParticipantRepository;
import com.labcalendar.labcalendarbackend.event.repository.EventRepository;
import com.labcalendar.labcalendarbackend.expense.repository.CardExpenseRepository;
import com.labcalendar.labcalendarbackend.member.repository.MemberRepository;
import com.labcalendar.labcalendarbackend.project.repository.ResearchProjectRepository;

/** Event CRUD (KAN-39). The range query lives in KAN-40 and reuses {@link #toResponse}. */
@Service
public class EventService {

    private final EventRepository events;
    private final EventParticipantRepository participants;
    private final CategoryRepository categories;
    private final MemberRepository members;
    private final ResearchProjectRepository projects;
    private final CardExpenseRepository cardExpenses;

    public EventService(EventRepository events, EventParticipantRepository participants,
            CategoryRepository categories, MemberRepository members,
            ResearchProjectRepository projects, CardExpenseRepository cardExpenses) {
        this.events = events;
        this.participants = participants;
        this.categories = categories;
        this.members = members;
        this.projects = projects;
        this.cardExpenses = cardExpenses;
    }

    @Transactional(readOnly = true)
    public EventResponse get(Long id) {
        return toResponse(findEvent(id));
    }

    @Transactional
    public EventResponse create(EventRequest request) {
        Category category = findCategory(request.categoryKey());
        Event event = events.save(Event.manual(
                category.getId(),
                request.title().trim(),
                request.normalizedDetail(),
                request.normalizedMemo(),
                request.startDate(),
                request.endDate()));
        replaceParticipants(event.getId(), request.normalizedParticipants());
        return toResponse(event);
    }

    @Transactional
    public EventResponse update(Long id, EventRequest request) {
        Event event = findEvent(id);
        requireClientWritable(event);

        Category category = findCategory(request.categoryKey());
        event.applyManualEdit(
                category.getId(),
                request.title().trim(),
                request.normalizedDetail(),
                request.normalizedMemo(),
                request.startDate(),
                request.endDate());
        replaceParticipants(event.getId(), request.normalizedParticipants());
        return toResponse(event);
    }

    @Transactional
    public void delete(Long id) {
        Event event = findEvent(id);
        requireClientWritable(event);
        // event_participant cascades in the schema, but the rows are deleted here too so the
        // in-memory persistence context does not keep stale children for the rest of the request.
        participants.deleteByEventId(event.getId());
        events.delete(event);
    }

    private Event findEvent(Long id) {
        return events.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    /**
     * An unknown category key is a client bug — the select is filled from {@code GET /api/categories}.
     * Reported as a plain validation failure rather than a field error for that reason.
     */
    private Category findCategory(String key) {
        return categories.findByCode(key)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
    }

    /**
     * Generated events are rebuilt by the batch and the sync, so an edit would be reverted on the
     * next run. Refused here as well as hidden in the UI — hiding a control is not a boundary.
     */
    private void requireClientWritable(Event event) {
        if (!event.source().isWritableByClient()) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    /** Positions must stay contiguous from 0, so the whole list is rewritten on every save. */
    private void replaceParticipants(Long eventId, List<String> names) {
        participants.deleteByEventId(eventId);
        // The delete must reach the database before the inserts, or the unique index on
        // (event_id, position) trips on rows the persistence context has not flushed yet.
        participants.flush();

        List<EventParticipant> rows = new ArrayList<>(names.size());
        for (int position = 0; position < names.size(); position++) {
            rows.add(EventParticipant.unlinked(eventId, names.get(position), position));
        }
        participants.saveAll(rows);
    }

    private EventResponse toResponse(Event event) {
        Category category = categories.findById(event.getCategoryId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));

        List<String> names = participants.findByEventIdOrderByPositionAsc(event.getId()).stream()
                .map(EventParticipant::getDisplayName)
                .toList();

        Display display = display(event);
        return new EventResponse(
                String.valueOf(event.getId()),
                display.title(),
                display.detail(),
                event.getStartDate(),
                event.getEndDate(),
                category.getCode(),
                event.getMemo(),
                names,
                event.getSource());
    }

    /**
     * What the calendar chip shows (docs/api-contract.md §6.2).
     *
     * <p>Generated events read their text from the originating row instead of the event row. The
     * batch and sync keep the two aligned, but reading the source directly means a rename cannot
     * leave a stale title behind on the schedule.
     */
    private Display display(Event event) {
        EventSource source = event.source();
        return switch (source) {
            case AUTO_GENERATED -> projects.findById(event.getResearchProjectId())
                    .map(project -> new Display(project.getName(), project.getSubmissionType()))
                    .orElseGet(() -> new Display(event.getTitle(), null));
            case GOOGLE_SYNC -> cardExpenses.findById(event.getCardExpenseId())
                    .map(expense -> new Display(expense.getCardName(), expense.getPurpose()))
                    .orElseGet(() -> new Display(event.getTitle(), null));
            case MANUAL -> new Display(event.getTitle(), manualDetail(event));
        };
    }

    /** A linked owner wins over the free-text value; the frontend does not send owners yet. */
    private String manualDetail(Event event) {
        if (event.getOwnerMemberId() == null) {
            return event.getManualDetail();
        }
        return members.findById(event.getOwnerMemberId())
                .map(member -> member.getName())
                .orElseGet(event::getManualDetail);
    }

    private record Display(String title, String detail) {
    }
}
