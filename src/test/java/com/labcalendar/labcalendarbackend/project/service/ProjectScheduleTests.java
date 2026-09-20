package com.labcalendar.labcalendarbackend.project.service;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The schedule arithmetic, pinned without a clock (KAN-47). */
class ProjectScheduleTests {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 19);

    @Test
    void countsDaysLeftUntilTheDeadline() {
        assertThat(ProjectSchedule.dDay(TODAY, LocalDate.of(2026, 9, 29))).isEqualTo(10);
    }

    @Test
    void theDeadlineItselfIsZero() {
        assertThat(ProjectSchedule.dDay(TODAY, TODAY)).isZero();
    }

    @Test
    void aPassedDeadlineIsNegative() {
        assertThat(ProjectSchedule.dDay(TODAY, LocalDate.of(2026, 9, 16))).isEqualTo(-3);
    }

    @Test
    void countsAcrossMonthAndYearBoundaries() {
        assertThat(ProjectSchedule.dDay(LocalDate.of(2026, 12, 28), LocalDate.of(2027, 1, 4)))
                .isEqualTo(7);
    }

    @Test
    void preparationStartsLeadTimeDaysBeforeTheDeadline() {
        assertThat(ProjectSchedule.preparationStart(LocalDate.of(2027, 4, 30), 21))
                .isEqualTo(LocalDate.of(2027, 4, 9));
    }

    @Test
    void aLeadTimeThatIsNotAWholeNumberOfWeeksIsKeptAsGiven() {
        assertThat(ProjectSchedule.preparationStart(LocalDate.of(2026, 9, 30), 10))
                .isEqualTo(LocalDate.of(2026, 9, 20));
    }

    @Test
    void zeroLeadTimeLeavesASingleDayOnTheDeadline() {
        assertThat(ProjectSchedule.preparationStart(LocalDate.of(2026, 9, 30), 0))
                .isEqualTo(LocalDate.of(2026, 9, 30));
    }

    @Test
    void countsBackAcrossAMonthBoundary() {
        assertThat(ProjectSchedule.preparationStart(LocalDate.of(2026, 3, 5), 21))
                .isEqualTo(LocalDate.of(2026, 2, 12));
    }
}
