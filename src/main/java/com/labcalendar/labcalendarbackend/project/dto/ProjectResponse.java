package com.labcalendar.labcalendarbackend.project.dto;

import java.time.LocalDate;

/**
 * One research project as the management screen shows it (docs/api-contract.md §7.1).
 *
 * <p>{@code dDay} and {@code preparationStartDate} are computed here rather than in the browser.
 * Worked out on the client they would shift with the device clock and time zone, so two people
 * could be looking at different countdowns for the same deadline (KAN-52).
 */
public record ProjectResponse(
        String id,
        String name,
        String submissionStage,
        LocalDate endDate,
        int leadTimeDays,
        boolean active,
        int dDay,
        LocalDate preparationStartDate) {
}
