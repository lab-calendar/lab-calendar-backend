package com.labcalendar.labcalendarbackend.project.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.labcalendar.labcalendarbackend.project.service.PreparationScheduleService;

/**
 * Nightly reconcile of every project's preparation schedule (KAN-49).
 *
 * <p>Registering and editing a project already syncs its own schedule, so this is the safety net
 * rather than the main path: it catches rows written straight to the database, and anything a
 * failed request left half-done.
 *
 * <p>03:00 Asia/Seoul — after midnight so a deadline that passed today is already yesterday's,
 * and late enough that nobody is watching the calendar while it runs.
 */
@Component
public class PreparationScheduleJob {

    private static final Logger log = LoggerFactory.getLogger(PreparationScheduleJob.class);

    private final PreparationScheduleService schedules;

    public PreparationScheduleJob(PreparationScheduleService schedules) {
        this.schedules = schedules;
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")
    public void run() {
        try {
            PreparationScheduleService.SyncResult result = schedules.syncAll();
            log.info("준비 기간 일정 동기화: 과제 {}건, 생성 {}건, 이동 {}건",
                    result.projects(), result.created(), result.moved());
        } catch (RuntimeException e) {
            // Letting this escape would stop the scheduler from running it again tomorrow.
            log.error("준비 기간 일정 동기화 실패", e);
        }
    }
}
