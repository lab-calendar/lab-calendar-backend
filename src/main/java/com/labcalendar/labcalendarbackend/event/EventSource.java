package com.labcalendar.labcalendarbackend.event;

import com.labcalendar.labcalendarbackend.common.exception.BusinessException;
import com.labcalendar.labcalendarbackend.common.exception.ErrorCode;

/**
 * Where an event came from (KAN-27 schema, {@code event.source}).
 *
 * <p>Only {@link #MANUAL} events may be written through the API. The others are rebuilt by the
 * lead-time batch (KAN-49) and the Google sync (KAN-58), so an edit here would be silently
 * reverted on the next run. Clients change them at the source instead.
 */
public enum EventSource {
    MANUAL,
    AUTO_GENERATED,
    GOOGLE_SYNC;

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
