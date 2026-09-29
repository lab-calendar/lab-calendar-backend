package com.labcalendar.labcalendarbackend.auth;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/**
 * The tier the caller signed in with, for the length of one request (KAN-35).
 *
 * <p>Set by {@code AuthInterceptor} once the session cookie has been checked, then read by the
 * services that have to narrow what they return. Keeping it here rather than passing the tier down
 * through every method means a new query cannot quietly skip the check by forgetting an argument.
 */
@Component
@RequestScope
public class CurrentSession {

    private AuthTier tier;

    public void authenticateAs(AuthTier tier) {
        this.tier = tier;
    }

    /** Null on the routes that need no session, such as the health probe and sign in. */
    public AuthTier tier() {
        return tier;
    }

    /**
     * Whether card spending has to be withheld from this caller.
     *
     * <p>Unauthenticated counts as restricted. Nothing reaches a service without going through the
     * interceptor today, but if a route ever does, it should show less rather than more.
     */
    public boolean seesCardData() {
        return tier == AuthTier.EDITOR;
    }
}
