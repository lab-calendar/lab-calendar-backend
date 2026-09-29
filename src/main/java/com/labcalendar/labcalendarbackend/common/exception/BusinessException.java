package com.labcalendar.labcalendarbackend.common.exception;

import java.util.Objects;

/** A domain failure with a public code, rather than an arbitrary public message. */
public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(Objects.requireNonNull(errorCode).message());
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() { return errorCode; }
}
