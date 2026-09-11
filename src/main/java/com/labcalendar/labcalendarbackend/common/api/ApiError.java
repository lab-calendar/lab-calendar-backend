package com.labcalendar.labcalendarbackend.common.api;

import java.util.Map;
import com.labcalendar.labcalendarbackend.common.exception.ErrorCode;

public record ApiError(String code, String message, Map<String, String> fieldErrors) {
    public ApiError {
        fieldErrors = Map.copyOf(fieldErrors);
    }

    public static ApiError of(ErrorCode code) {
        return of(code, Map.of());
    }

    public static ApiError of(ErrorCode code, Map<String, String> fields) {
        return new ApiError(code.name(), code.message(), fields);
    }
}
