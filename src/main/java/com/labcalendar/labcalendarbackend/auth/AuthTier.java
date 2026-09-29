package com.labcalendar.labcalendarbackend.auth;

/**
 * Access level granted by the shared password (KAN-21).
 *
 * <p>There are no personal accounts. The lab has two passwords: one for people who run the
 * calendar and one for outside advisers who may only look.
 */
public enum AuthTier {
    /** Full access, including writes. */
    EDITOR,
    /** Read only, and card spending is withheld entirely (KAN-35). */
    VIEWER
}
