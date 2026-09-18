package com.labcalendar.labcalendarbackend.event.dto;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Create and update payload for a manual event (KAN-39).
 *
 * <p>The client never sends {@code id} or {@code source}: creation is always {@code MANUAL} and
 * the source of an existing event cannot be reassigned. There are no time-of-day fields — the
 * first API round is all-day only (docs/api-contract.md §4.3).
 */
@EndDateNotBeforeStartDate
public record EventRequest(
        @NotBlank(message = "제목을 입력해 주세요.")
        @Size(max = 500, message = "제목은 500자 이내로 입력해 주세요.")
        String title,

        /** Submission stage, owner or spend purpose, depending on the category. */
        String detail,

        @NotNull(message = "시작일을 선택해 주세요.")
        LocalDate startDate,

        @NotNull(message = "종료일을 선택해 주세요.")
        LocalDate endDate,

        @NotBlank(message = "항목 유형을 선택해 주세요.")
        String categoryKey,

        String memo,

        List<@Size(max = 100, message = "참석자 이름은 100자 이내로 입력해 주세요.") String> participants) {

    /**
     * Attendee names as they should be stored: trimmed, blanks dropped, order kept.
     *
     * <p>Duplicate names collapse, which is what the current free-text input means — two people
     * with the same name need member IDs to tell apart, and that arrives with KAN-41.
     */
    public List<String> normalizedParticipants() {
        if (participants == null) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String name : participants) {
            if (name == null) {
                continue;
            }
            String trimmed = name.trim();
            if (!trimmed.isEmpty()) {
                unique.add(trimmed);
            }
        }
        return List.copyOf(unique);
    }

    /** Empty strings and whitespace mean "not provided", so they are stored as null. */
    public String normalizedDetail() {
        return blankToNull(detail);
    }

    public String normalizedMemo() {
        return blankToNull(memo);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
