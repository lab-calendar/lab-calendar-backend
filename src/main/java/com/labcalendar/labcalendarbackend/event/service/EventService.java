package com.labcalendar.labcalendarbackend.event.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.labcalendar.labcalendarbackend.category.entity.Category;
import com.labcalendar.labcalendarbackend.category.repository.CategoryRepository;
import com.labcalendar.labcalendarbackend.common.exception.BusinessException;
import com.labcalendar.labcalendarbackend.common.exception.ErrorCode;
import com.labcalendar.labcalendarbackend.event.dto.EventRequest;
import com.labcalendar.labcalendarbackend.event.dto.EventResponse;
import com.labcalendar.labcalendarbackend.event.entity.Event;
import com.labcalendar.labcalendarbackend.event.entity.EventParticipant;
import com.labcalendar.labcalendarbackend.event.repository.EventParticipantRepository;
import com.labcalendar.labcalendarbackend.event.repository.EventRepository;
import com.labcalendar.labcalendarbackend.expense.entity.CardExpense;
import com.labcalendar.labcalendarbackend.expense.repository.CardExpenseRepository;
import com.labcalendar.labcalendarbackend.member.entity.Member;
import com.labcalendar.labcalendarbackend.member.repository.MemberRepository;
import com.labcalendar.labcalendarbackend.project.entity.ResearchProject;
import com.labcalendar.labcalendarbackend.project.repository.ResearchProjectRepository;

/** Event CRUD (KAN-39) and range listing (KAN-40). */
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

    /**
     * Events overlapping {@code [from, to]}, optionally narrowed to category keys (KAN-40).
     *
     * @param categoryKeys {@code null} means every category. An empty selection means the caller
     *     asked for none, which is an empty result rather than everything.
     */
    @Transactional(readOnly = true)
    public List<EventResponse> list(LocalDate from, LocalDate to, List<String> categoryKeys) {
        if (from.isAfter(to)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (categoryKeys == null) {
            return assemble(events.findOverlapping(from, to));
        }

        Set<Long> categoryIds = resolveCategoryIds(categoryKeys);
        if (categoryIds.isEmpty()) {
            return List.of();
        }
        return assemble(events.findOverlappingInCategories(from, to, categoryIds));
    }

    @Transactional(readOnly = true)
    public EventResponse get(Long id) {
        return single(findEvent(id));
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
        return single(event);
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
        return single(event);
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

    /** Blank entries are dropped so {@code ?categories=} reads as an empty selection, not a bad one. */
    private Set<Long> resolveCategoryIds(List<String> keys) {
        Set<Long> ids = new LinkedHashSet<>();
        for (String key : keys) {
            if (key == null || key.isBlank()) {
                continue;
            }
            ids.add(findCategory(key.trim()).getId());
        }
        return ids;
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

    private EventResponse single(Event event) {
        return assemble(List.of(event)).get(0);
    }

    /**
     * Builds responses for a whole page of events.
     *
     * <p>Every lookup the display text needs — participants, categories, and the originating
     * project, card or member rows — is fetched once for the batch rather than per event, so the
     * cost stays flat as a month fills up.
     */
    private List<EventResponse> assemble(List<Event> sourceEvents) {
        if (sourceEvents.isEmpty()) {
            return List.of();
        }

        Map<Long, List<String>> namesByEvent = participantNames(collectIds(sourceEvents, Event::getId));
        Map<Long, Category> categoryById =
                byId(categories.findAllById(collectIds(sourceEvents, Event::getCategoryId)), Category::getId);
        Map<Long, ResearchProject> projectById =
                byId(projects.findAllById(collectIds(sourceEvents, Event::getResearchProjectId)), ResearchProject::getId);
        Map<Long, CardExpense> expenseById =
                byId(cardExpenses.findAllById(collectIds(sourceEvents, Event::getCardExpenseId)), CardExpense::getId);
        Map<Long, Member> memberById =
                byId(members.findAllById(collectIds(sourceEvents, Event::getOwnerMemberId)), Member::getId);

        List<EventResponse> responses = new ArrayList<>(sourceEvents.size());
        for (Event event : sourceEvents) {
            Category category = categoryById.get(event.getCategoryId());
            if (category == null) {
                // A non-null FK guarantees the row exists; a miss means the data is broken.
                throw new BusinessException(ErrorCode.INTERNAL_ERROR);
            }

            Display display = display(event, projectById, expenseById, memberById);
            responses.add(new EventResponse(
                    String.valueOf(event.getId()),
                    display.title(),
                    display.detail(),
                    event.getStartDate(),
                    event.getEndDate(),
                    category.getCode(),
                    event.getMemo(),
                    namesByEvent.getOrDefault(event.getId(), List.of()),
                    event.getSource()));
        }
        return responses;
    }

    private Map<Long, List<String>> participantNames(Collection<Long> eventIds) {
        Map<Long, List<String>> namesByEvent = new HashMap<>();
        for (EventParticipant participant : participants.findByEventIdInOrderByEventIdAscPositionAsc(eventIds)) {
            namesByEvent.computeIfAbsent(participant.getEventId(), key -> new ArrayList<>())
                    .add(participant.getDisplayName());
        }
        return namesByEvent;
    }

    /**
     * What the calendar chip shows (docs/api-contract.md §6.2).
     *
     * <p>Generated events read their text from the originating row instead of the event row. The
     * batch and sync keep the two aligned, but reading the source directly means a rename cannot
     * leave a stale title behind on the schedule.
     */
    private Display display(Event event, Map<Long, ResearchProject> projectById,
            Map<Long, CardExpense> expenseById, Map<Long, Member> memberById) {
        return switch (event.source()) {
            case AUTO_GENERATED -> {
                ResearchProject project = projectById.get(event.getResearchProjectId());
                yield project == null
                        ? new Display(event.getTitle(), null)
                        : new Display(project.getName(), project.getSubmissionType());
            }
            case GOOGLE_SYNC -> {
                CardExpense expense = expenseById.get(event.getCardExpenseId());
                yield expense == null
                        ? new Display(event.getTitle(), null)
                        : new Display(expense.getCardName(), expense.getPurpose());
            }
            case MANUAL -> new Display(event.getTitle(), manualDetail(event, memberById));
        };
    }

    /** A linked owner wins over the free-text value; the frontend does not send owners yet. */
    private String manualDetail(Event event, Map<Long, Member> memberById) {
        if (event.getOwnerMemberId() == null) {
            return event.getManualDetail();
        }
        Member owner = memberById.get(event.getOwnerMemberId());
        return owner == null ? event.getManualDetail() : owner.getName();
    }

    private static Set<Long> collectIds(List<Event> sourceEvents, Function<Event, Long> pick) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Event event : sourceEvents) {
            Long id = pick.apply(event);
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    private static <T> Map<Long, T> byId(Iterable<T> rows, Function<T, Long> idOf) {
        Map<Long, T> byId = new HashMap<>();
        rows.forEach(row -> byId.put(idOf.apply(row), row));
        return byId;
    }

    private record Display(String title, String detail) {
    }
}
