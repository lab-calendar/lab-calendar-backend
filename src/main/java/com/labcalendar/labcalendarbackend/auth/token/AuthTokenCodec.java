package com.labcalendar.labcalendarbackend.auth.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import com.labcalendar.labcalendarbackend.auth.AuthProperties;
import com.labcalendar.labcalendarbackend.auth.AuthTier;

/**
 * Signs and reads the session token (KAN-34).
 *
 * <p>The token carries its own tier and expiry and is signed with a server secret, so there is no
 * session store to keep, share between instances, or lose on restart. Nothing secret is inside it
 * — it says what the holder may do, and the signature is what makes that claim trustworthy.
 *
 * <p>Revoking a single session is therefore not possible; rotating the secret signs everyone out.
 * For a lab sharing two passwords that is the right trade.
 */
@Component
public class AuthTokenCodec {

    private static final String ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final AuthProperties properties;
    private final Clock clock;

    public AuthTokenCodec(AuthProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public IssuedToken issue(AuthTier tier) {
        Instant expiresAt = clock.instant().plus(properties.getTokenTtl());
        String payload = ENCODER.encodeToString(
                (tier.name() + "|" + expiresAt.getEpochSecond()).getBytes(StandardCharsets.UTF_8));
        return new IssuedToken(payload + "." + sign(payload), properties.getTokenTtl());
    }

    /** Empty for anything we did not sign, cannot parse, or that has run out. */
    public Optional<AuthTier> read(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        int separator = token.lastIndexOf('.');
        if (separator <= 0 || separator == token.length() - 1) {
            return Optional.empty();
        }

        String payload = token.substring(0, separator);
        String signature = token.substring(separator + 1);
        if (!MessageDigest.isEqual(
                sign(payload).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }
        return parse(payload);
    }

    private Optional<AuthTier> parse(String payload) {
        String decoded;
        try {
            decoded = new String(DECODER.decode(payload), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }

        int separator = decoded.indexOf('|');
        if (separator <= 0) {
            return Optional.empty();
        }
        try {
            if (Instant.ofEpochSecond(Long.parseLong(decoded.substring(separator + 1)))
                    .isBefore(clock.instant())) {
                return Optional.empty();
            }
            return Optional.of(AuthTier.valueOf(decoded.substring(0, separator)));
        } catch (IllegalArgumentException unusable) {
            return Optional.empty();
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(
                    properties.getTokenSecret().getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return ENCODER.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException impossible) {
            // HmacSHA256 is required of every JRE; a failure here is not something to recover from.
            throw new IllegalStateException("토큰 서명에 실패했습니다.", impossible);
        }
    }

    public record IssuedToken(String value, java.time.Duration ttl) {
    }
}
