package com.labcalendar.labcalendarbackend.expense.importing;

/** Safe service codes; HTTP mapping is added by KAN-59. */
public class LedgerSyncException extends RuntimeException {
    public LedgerSyncException(String code) { super(code); }
}
