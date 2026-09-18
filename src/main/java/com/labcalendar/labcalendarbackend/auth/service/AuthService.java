package com.labcalendar.labcalendarbackend.auth.service;

import java.util.Optional;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import com.labcalendar.labcalendarbackend.auth.AuthProperties;
import com.labcalendar.labcalendarbackend.auth.AuthTier;
import com.labcalendar.labcalendarbackend.auth.token.AuthTokenCodec;
import com.labcalendar.labcalendarbackend.common.exception.BusinessException;
import com.labcalendar.labcalendarbackend.common.exception.ErrorCode;

/** Shared-password sign in (KAN-34). */
@Service
public class AuthService {

    private final AuthProperties properties;
    private final AuthTokenCodec tokens;
    private final LoginAttemptLimiter limiter;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthService(AuthProperties properties, AuthTokenCodec tokens, LoginAttemptLimiter limiter) {
        this.properties = properties;
        this.tokens = tokens;
        this.limiter = limiter;
    }

    /**
     * Works out which of the two passwords was given and issues a token for that tier.
     *
     * <p>Both hashes are always checked, even once the first has matched. Returning early would
     * make an editor password answer in half the time of a viewer one, and that difference is
     * readable from outside. A wrong password gets the same 401 either way — which of the two it
     * failed against is not something the caller learns.
     */
    public Session login(String password, String client) {
        if (limiter.isBlocked(client)) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
        }

        boolean editorMatched = encoder.matches(password, properties.getEditorPasswordHash());
        boolean viewerMatched = encoder.matches(password, properties.getViewerPasswordHash());

        if (!editorMatched && !viewerMatched) {
            limiter.recordFailure(client);
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        limiter.recordSuccess(client);
        // Editing wins if both somehow match, so a misconfiguration cannot quietly downgrade an editor.
        AuthTier tier = editorMatched ? AuthTier.EDITOR : AuthTier.VIEWER;
        return new Session(tier, tokens.issue(tier));
    }

    /** The tier a token grants, or empty when there is no usable token. */
    public Optional<AuthTier> resolve(String token) {
        return tokens.read(token);
    }

    public record Session(AuthTier tier, AuthTokenCodec.IssuedToken token) {
    }
}
