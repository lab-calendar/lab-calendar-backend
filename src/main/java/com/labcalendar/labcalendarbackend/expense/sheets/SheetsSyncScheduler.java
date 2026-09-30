package com.labcalendar.labcalendarbackend.expense.sheets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import com.labcalendar.labcalendarbackend.expense.importing.LedgerSyncService;

/**
 * Fires the sheet sync on a timer (KAN-88).
 *
 * <p>Holds no logic of its own beyond deciding not to start. If an upload or a previous run is
 * still going, this turn is skipped rather than queued — the runs are not each other's backlog,
 * and the next one reads the same sheet a few minutes later anyway.
 */
class SheetsSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SheetsSyncScheduler.class);

    private final SheetsSyncService service;
    private final LedgerSyncService sync;

    SheetsSyncScheduler(SheetsSyncService service, LedgerSyncService sync) {
        this.service = service;
        this.sync = sync;
    }

    @Scheduled(cron = "${lab-calendar.sheets.cron}", zone = "Asia/Seoul")
    void synchronize() {
        if (sync.busy()) {
            /*
             * Waiting here would pile runs up behind a long upload and then apply them one after
             * another against a sheet that has not changed in between.
             */
            log.info("시트 동기화를 건너뜁니다 — 다른 가져오기가 진행 중입니다.");
            return;
        }

        SheetsSyncService.Outcome outcome = service.run(LedgerSyncService.SCHEDULED);

        if (!outcome.applied()) {
            // Never throws: the timer has nobody to report to, so the reason is in the history too.
            log.warn("시트 동기화 실패: {}", outcome.failure());
            return;
        }
        if (!outcome.braked().isEmpty()) {
            log.warn("시트 동기화에서 {}개 월을 안전장치로 건너뛰었습니다: {}",
                    outcome.braked().size(), outcome.braked());
        }
        log.info("시트 동기화 완료 — 추가 {}건, 삭제 {}건, 변화 없음 {}건",
                outcome.added(), outcome.removed(), outcome.unchanged());
    }
}
