package com.labcalendar.labcalendarbackend.auth.dto;

import com.labcalendar.labcalendarbackend.auth.AuthTier;

/**
 * Who the caller currently is (KAN-34).
 *
 * <p>The frontend uses this for its route guard and to decide which controls to show. Hiding a
 * button is a convenience, not a boundary — the server enforces the same tier on every request
 * (KAN-35).
 */
public record SessionResponse(boolean authenticated, AuthTier tier) {

    public static SessionResponse of(AuthTier tier) {
        return new SessionResponse(true, tier);
    }

    public static SessionResponse anonymous() {
        return new SessionResponse(false, null);
    }
}
