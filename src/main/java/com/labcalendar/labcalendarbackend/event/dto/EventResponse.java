package com.labcalendar.labcalendarbackend.event.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * One event as the calendar draws it (docs/api-contract.md §6.1).
 *
 * <p>{@code title} and {@code detail} are composed by the server. For generated events they come
 * from the originating project or card row rather than the event row, so a renamed project cannot
 * drift away from the schedule that represents it.
 *
 * <p>{@code endDate} is the last day shown and is inclusive. The server never adds or subtracts a
 * day; the exclusive-end conversion FullCalendar needs happens only in the frontend.
 */
public record EventResponse(
        String id,
        String title,
        String detail,
        LocalDate startDate,
        LocalDate endDate,
        String categoryKey,
        String memo,
        List<String> participants,
        String source) {
}
