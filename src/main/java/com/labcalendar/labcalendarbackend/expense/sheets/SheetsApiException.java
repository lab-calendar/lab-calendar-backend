package com.labcalendar.labcalendarbackend.expense.sheets;

import java.util.Objects;

/**
 * A reason the sheet could not be read, in words a person can act on (KAN-89).
 *
 * <p>Google's own messages can carry the document title and the caller's identity, and the run
 * writes its reason into {@code sync_log} where the lab reads it. So the outside message is never
 * forwarded — only one of these codes is, each mapped to what the reader should go and do.
 */
public class SheetsApiException extends RuntimeException {

    public enum Code {
        /** The key was rejected: revoked, or this machine's clock has drifted too far. */
        CREDENTIALS_REJECTED("시트 접근 자격이 거절되었습니다. 서비스 계정 키가 폐기되었거나 서버 시계가 어긋났습니다."),
        /** The key file itself will not parse or is missing fields. */
        CREDENTIALS_INVALID("서비스 계정 키를 읽을 수 없습니다. 키 JSON 을 다시 넣어 주세요."),
        /** Shared access was withdrawn, or the Sheets API was switched off for the project. */
        PERMISSION_DENIED("시트를 볼 권한이 없습니다. 공유가 해제되었거나 Sheets API 사용 설정이 꺼졌습니다."),
        /** The id points at nothing: renamed is fine, moved to trash is not. */
        SPREADSHEET_NOT_FOUND("시트를 찾을 수 없습니다. 문서가 삭제되었거나 ID 가 바뀌었습니다."),
        /** Rate limited or Google is unwell; the next run will try again. */
        TEMPORARILY_UNAVAILABLE("구글이 지금은 응답하지 않습니다. 다음 회차에 다시 시도합니다."),
        /** Connect or read timed out. */
        TIMED_OUT("시트를 읽는 데 시간이 너무 오래 걸립니다."),
        /** The answer was larger than the caps allow. */
        RESPONSE_TOO_LARGE("시트가 한 번에 읽을 수 있는 크기를 넘었습니다."),
        /** Well-formed HTTP, but not the shape the API documents. */
        UNREADABLE_RESPONSE("구글의 응답을 해석할 수 없습니다."),
        /** No tab in the window looked like a month. Read on its own this is not a reason to delete. */
        NO_MONTH_TABS("읽을 월 탭이 없습니다. 반영을 건너뜁니다.");

        private final String message;
        Code(String message) { this.message = message; }
        public String message() { return message; }
    }

    private final Code code;

    public SheetsApiException(Code code) {
        super(Objects.requireNonNull(code).message());
        this.code = code;
    }

    public Code code() { return code; }

    /** Whether trying the same call again could plausibly succeed. */
    public boolean retryable() {
        return code == Code.TEMPORARILY_UNAVAILABLE || code == Code.TIMED_OUT;
    }
}
