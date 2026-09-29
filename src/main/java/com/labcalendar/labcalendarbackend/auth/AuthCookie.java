package com.labcalendar.labcalendarbackend.auth;

import java.time.Duration;
import org.springframework.http.ResponseCookie;

/**
 * The session cookie (KAN-34).
 *
 * <p>Held in an HttpOnly cookie rather than returned for the page to store: script cannot read it,
 * so an injected script cannot walk off with a session. The frontend is served from the same
 * origin as the API in both production (nginx proxies {@code /api/}) and development (Vite dev
 * proxy), so the cookie rides along on its own and {@code SameSite=Lax} is enough to keep it off
 * cross-site requests.
 */
public final class AuthCookie {

    public static final String NAME = "lab_calendar_session";

    private AuthCookie() {
    }

    public static ResponseCookie issue(String value, Duration ttl, boolean secure) {
        return base(value, secure).maxAge(ttl).build();
    }

    /** Same attributes with an immediate expiry, which is what actually removes it. */
    public static ResponseCookie clear(boolean secure) {
        return base("", secure).maxAge(0).build();
    }

    private static ResponseCookie.ResponseCookieBuilder base(String value, boolean secure) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/");
    }
}
