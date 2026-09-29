package com.labcalendar.labcalendarbackend.common.api;

/** Explicit wrapper: controllers return this once, including for list responses. */
public record ApiResponse<T>(T data) {
    public static <T> ApiResponse<T> of(T data) {
        return new ApiResponse<>(data);
    }
}
