package com.labcalendar.labcalendarbackend.expense.importing;

/** Safe service codes; HTTP mapping is added by KAN-59. */
public class LedgerSyncException extends RuntimeException {
    public enum Code {
        NO_APPLICABLE_MONTHS, IMPORT_FAILED_LOG_UNAVAILABLE, IMPORT_FAILED,
        OUTER_TRANSACTION_NOT_SUPPORTED, NO_MONTHS, DUPLICATE_MONTH,
        UNSUPPORTED_DATABASE_YEAR, ROW_MONTH_MISMATCH
    }
    private final Code code;
    public LedgerSyncException(Code code) {
        super(java.util.Objects.requireNonNull(code).name());
        this.code = code;
    }
    public Code code() { return code; }
}
