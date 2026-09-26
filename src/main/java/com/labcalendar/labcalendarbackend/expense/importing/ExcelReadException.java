package com.labcalendar.labcalendarbackend.expense.importing;

/** Safe error codes only: never expose workbook contents or POI parser messages. */
public class ExcelReadException extends RuntimeException {
    public enum Code {
        INVALID_FILE_TYPE, EMPTY_FILE, FILE_TOO_LARGE, INVALID_WORKBOOK,
        EMPTY_WORKBOOK, WORKBOOK_TOO_LARGE, FORMULA_CACHE_MISSING
    }

    private final Code code;

    public ExcelReadException(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code code() { return code; }
}
