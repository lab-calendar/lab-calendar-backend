package com.labcalendar.labcalendarbackend.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** The shared password. Which of the two it is decides the tier (KAN-34). */
public record LoginRequest(
        @NotBlank(message = "비밀번호를 입력해 주세요.")
        String password) {
}
