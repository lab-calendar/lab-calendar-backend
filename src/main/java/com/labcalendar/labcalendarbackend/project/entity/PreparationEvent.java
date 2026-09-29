package com.labcalendar.labcalendarbackend.project.entity;

import java.time.LocalDate;
import com.labcalendar.labcalendarbackend.event.entity.Event;

/**
 * What a project's preparation schedule should look like (KAN-49).
 *
 * <p>Kept apart from the batch so the arithmetic and the wording can be checked without a
 * database. The stored title is not what the calendar shows — a generated event reads its text
 * from the project row at response time (docs/api-contract.md §6.2) so a rename cannot leave a
 * stale title behind. It is written anyway because the schema requires a non-blank title, and
 * because someone reading the table directly should be able to tell what the row is.
 */
public record PreparationEvent(LocalDate startDate, LocalDate endDate, String title) {

    /** 기획서 3.1 — "[작성 요망] BRL 과제 연차보고서 준비 시작". */
    private static final String PREFIX = "[작성 요망] ";

    public static PreparationEvent of(ResearchProject project) {
        LocalDate endDate = project.getEndDate();
        return new PreparationEvent(
                endDate.minusDays(project.getLeadTimeDays()),
                endDate,
                title(project));
    }

    private static String title(ResearchProject project) {
        String stage = project.getSubmissionType();
        String subject = stage == null || stage.isBlank()
                ? project.getName()
                : project.getName() + " " + stage.trim();
        return PREFIX + subject + " 준비 시작";
    }

    /** Whether an existing row already says this. Title is excluded — the display ignores it. */
    public boolean matches(Event event) {
        return startDate.equals(event.getStartDate()) && endDate.equals(event.getEndDate());
    }
}
