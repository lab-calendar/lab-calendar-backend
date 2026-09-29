package com.labcalendar.labcalendarbackend.auth.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Slows down guessing at the shared password (KAN-34).
 *
 * <p>A shared password is the whole of the lock here — there is no username to get wrong first —
 * so an unthrottled login is worth guessing at. Failures are counted per client within a fixed
 * window; a success clears the count.
 *
 * <p>Kept in memory, which is enough for the single instance this runs on. More than one instance
 * would each hold their own count and the effective limit would multiply; that needs shared state
 * and is worth revisiting if the deployment ever grows (noted on KAN-59).
 */
@Component
public class LoginAttemptLimiter {

    private static final int MAX_FAILURES = 10;
    private static final Duration WINDOW = Duration.ofMinutes(5);
    /** Stops the map growing without bound when many clients fail once and leave. */
    private static final int PRUNE_THRESHOLD = 1_000;

    private final Map<String, Attempts> attemptsByClient = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginAttemptLimiter(Clock clock) {
        this.clock = clock;
    }

    public boolean isBlocked(String client) {
        Attempts attempts = attemptsByClient.get(client);
        return attempts != null && attempts.isCurrent(clock.instant()) && attempts.count >= MAX_FAILURES;
    }

    public void recordFailure(String client) {
        Instant now = clock.instant();
        if (attemptsByClient.size() > PRUNE_THRESHOLD) {
            attemptsByClient.values().removeIf(attempts -> !attempts.isCurrent(now));
        }
        attemptsByClient.compute(client, (key, existing) ->
                existing != null && existing.isCurrent(now)
                        ? new Attempts(existing.windowStart, existing.count + 1)
                        : new Attempts(now, 1));
    }

    public void recordSuccess(String client) {
        attemptsByClient.remove(client);
    }

    private record Attempts(Instant windowStart, int count) {
        boolean isCurrent(Instant now) {
            return windowStart.plus(WINDOW).isAfter(now);
        }
    }
}
