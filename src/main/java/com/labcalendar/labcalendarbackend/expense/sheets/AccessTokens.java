package com.labcalendar.labcalendarbackend.expense.sheets;

/**
 * Where the HTTP client gets its bearer token (KAN-88).
 *
 * <p>A seam, so the request and status handling can be tested without a key or a token endpoint.
 * {@link GoogleServiceAccountTokens} is the real one.
 */
interface AccessTokens {

    String accessToken();

    /** Throw away what is cached, because the server has just refused it. */
    void forget();
}
