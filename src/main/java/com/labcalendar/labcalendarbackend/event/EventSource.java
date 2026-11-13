package com.labcalendar.labcalendarbackend.event;

import com.labcalendar.labcalendarbackend.common.exception.BusinessException;
import com.labcalendar.labcalendarbackend.common.exception.ErrorCode;

/**
 * Where an event came from (KAN-27 schema, {@code event.source}).
 *
 * <p>Only {@link #MANUAL} events may be written through the API. The others are rebuilt by the
 * lead-time batch (KAN-49) and by the card ledger import (KAN-58), so an edit here would be
 * silently reverted on the next run. Clients change them at the source instead.
 *
 * <p>{@link #CARD_IMPORT} was called {@code GOOGLE_SYNC} until KAN-76. The old name came from
 * reading the Google API directly; today the value is written by two paths — an uploaded
 * spreadsheet and the sheet sync — and neither reads as "Google sync". What they share is that
 * the card ledger was imported.
 */
public enum EventSource {
    MANUAL,
    AUTO_GENERATED,
    CARD_IMPORT;

    public boolean isWritableByClient() {
        return this == MANUAL;
    }

    /** Stored values come from our own schema; an unknown one means the row is corrupt. */
    public static EventSource of(String stored) {
        try {
            return valueOf(stored);
        } catch (IllegalArgumentException | NullPointerException cause) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }
}
