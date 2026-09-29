package com.labcalendar.labcalendarbackend.project.dto;

import java.time.LocalDate;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Create and update payload for a research project (KAN-47, docs/api-contract.md §7.4).
 *
 * <p>{@code dDay} and {@code preparationStartDate} are never accepted from the client: the server
 * works them out so everyone sees the same countdown.
 */
public record ProjectRequest(
        @NotBlank(message = "과제명을 입력해 주세요.")
        @Size(max = 200, message = "과제명은 200자 이내로 입력해 주세요.")
        String name,

        /** Optional: a conference application has no submission stage to speak of. */
        @Size(max = 100, message = "제출 단계는 100자 이내로 입력해 주세요.")
        String submissionStage,

        @NotNull(message = "제출 마감일을 선택해 주세요.")
        LocalDate endDate,

        @NotNull(message = "준비 기간을 입력해 주세요.")
        @Min(value = 0, message = "준비 기간은 0일 이상이어야 합니다.")
        @Max(value = 182, message = "준비 기간은 182일 이하여야 합니다.")
        Integer leadTimeDays,

        @NotNull(message = "캘린더 표시 여부를 지정해 주세요.")
        Boolean active) {

    /** Empty and whitespace-only mean "not provided", so they are stored as null. */
    public String normalizedSubmissionStage() {
        if (submissionStage == null) {
            return null;
        }
        String trimmed = submissionStage.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
