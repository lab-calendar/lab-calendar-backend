package com.labcalendar.labcalendarbackend.member.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Create and update payload for a lab member (KAN-41).
 *
 * <p>Names are deliberately not unique. Two researchers can share a name, and it is their id that
 * tells them apart — rejecting or merging on the name would lose one of them.
 */
public record MemberRequest(
        @NotBlank(message = "이름을 입력해 주세요.")
        @Size(max = 100, message = "이름은 100자 이내로 입력해 주세요.")
        String name,

        @NotNull(message = "재직 여부를 지정해 주세요.")
        Boolean active) {
}
