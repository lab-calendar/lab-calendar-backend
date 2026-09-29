package com.labcalendar.labcalendarbackend.expense.importing;

import org.junit.jupiter.api.Test;
import com.labcalendar.labcalendarbackend.common.exception.GlobalExceptionHandler;
import static org.assertj.core.api.Assertions.assertThat;

class ImportErrorResponseTests {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void syncCodeDoesNotDependOnExceptionMessage() {
        var exception = new LedgerSyncException(LedgerSyncException.Code.NO_APPLICABLE_MONTHS) {
            @Override public String getMessage() { return null; }
        };
        var response = handler.syncFailure(exception);
        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody().code()).isEqualTo("NO_APPLICABLE_MONTHS");
    }

    @Test
    void invalidNameAndUnreadableFileHaveSpecificGuidance() {
        assertThat(handler.importFailure(new ImportApiException(400, "INVALID_FILE_NAME")).getBody().message())
                .contains("191자");
        assertThat(handler.importFailure(new ImportApiException(400, "INVALID_WORKBOOK")).getBody().message())
                .contains("읽을 수 없습니다");
        assertThat(handler.importFailure(new ImportApiException(400, "PREVIEW_TOKEN_INVALID")).getBody().message())
                .contains("다시 미리보기");
    }
}
