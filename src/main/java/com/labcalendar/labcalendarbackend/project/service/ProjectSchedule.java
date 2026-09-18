package com.labcalendar.labcalendarbackend.project.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * The two values the server works out for a project (KAN-47, KAN-48).
 *
 * <p>Pure functions of a given day so the arithmetic can be tested without a clock, and so the
 * caller has to be explicit about which day it means.
 */
final class ProjectSchedule {

    private ProjectSchedule() {
    }

    /** Days left until the deadline: 0 on the day itself, negative once it has passed. */
    static int dDay(LocalDate today, LocalDate endDate) {
        return (int) ChronoUnit.DAYS.between(today, endDate);
    }

    /**
     * The day preparation starts, counted back from the deadline.
     *
     * <p>A lead time of 0 leaves a single-day period on the deadline itself.
     */
    static LocalDate preparationStart(LocalDate endDate, int leadTimeDays) {
        return endDate.minusDays(leadTimeDays);
    }
}
