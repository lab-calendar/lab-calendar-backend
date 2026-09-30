package com.labcalendar.labcalendarbackend.expense.sheets;

import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.labcalendar.labcalendarbackend.auth.CurrentSession;
import com.labcalendar.labcalendarbackend.common.api.ApiResponse;
import com.labcalendar.labcalendarbackend.common.exception.BusinessException;
import com.labcalendar.labcalendarbackend.common.exception.ErrorCode;
import com.labcalendar.labcalendarbackend.expense.importing.ImportApiException;
import com.labcalendar.labcalendarbackend.expense.importing.LedgerSyncService;

/**
 * Runs the sheet sync now, instead of waiting for the timer (KAN-88).
 *
 * <p>For the case where somebody has just fixed the sheet and wants to see it in the calendar
 * rather than wonder whether the next run will pick it up.
 *
 * <p>Registered even when the integration is switched off, so the answer is "this is not turned
 * on" rather than a 404 that reads like a broken deployment.
 */
@RestController
@RequestMapping("/api/card-expenses/sheets-sync")
public class SheetsSyncController {

    private final CurrentSession session;
    private final ObjectProvider<SheetsSyncService> service;

    public SheetsSyncController(CurrentSession session, ObjectProvider<SheetsSyncService> service) {
        this.session = session;
        this.service = service;
    }

    public record Response(boolean applied, String failure, int added, int removed, int unchanged,
            List<SheetsSyncService.Braked> braked) {}

    @PostMapping
    public ApiResponse<Response> synchronizeNow() {
        // Card spending is editor-only, and this writes it (KAN-21).
        if (!session.seesCardData()) throw new BusinessException(ErrorCode.FORBIDDEN);

        SheetsSyncService sheets = service.getIfAvailable();
        if (sheets == null) throw new ImportApiException(503, "SHEETS_SYNC_DISABLED");

        /*
         * Triggered by a person, so it is logged as MANUAL — the history screen reads that column
         * to tell "somebody did this" from "the timer did this".
         */
        SheetsSyncService.Outcome outcome = sheets.run(LedgerSyncService.MANUAL);
        return ApiResponse.of(new Response(outcome.applied(), outcome.failure(), outcome.added(),
                outcome.removed(), outcome.unchanged(), outcome.braked()));
    }
}
