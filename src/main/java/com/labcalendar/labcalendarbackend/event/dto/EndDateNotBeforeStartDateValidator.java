package com.labcalendar.labcalendarbackend.event.dto;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class EndDateNotBeforeStartDateValidator
        implements ConstraintValidator<EndDateNotBeforeStartDate, EventRequest> {

    @Override
    public boolean isValid(EventRequest request, ConstraintValidatorContext context) {
        if (request == null || request.startDate() == null || request.endDate() == null) {
            // A missing date is @NotNull's to report; saying it twice would show two messages.
            return true;
        }
        if (!request.endDate().isBefore(request.startDate())) {
            return true;
        }

        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                .addPropertyNode("endDate")
                .addConstraintViolation();
        return false;
    }
}
