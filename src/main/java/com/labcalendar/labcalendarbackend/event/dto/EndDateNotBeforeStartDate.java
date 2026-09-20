package com.labcalendar.labcalendarbackend.event.dto;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Rejects a period that ends before it starts (KAN-39).
 *
 * <p>Declared on the request type rather than a field because it compares two of them. The
 * violation is still reported under {@code endDate} so the client can attach it to that input —
 * {@code fieldErrors} keys are part of the API contract.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = EndDateNotBeforeStartDateValidator.class)
public @interface EndDateNotBeforeStartDate {

    String message() default "종료일은 시작일보다 빠를 수 없습니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
