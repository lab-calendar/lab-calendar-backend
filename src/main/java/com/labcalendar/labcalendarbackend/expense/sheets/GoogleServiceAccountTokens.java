package com.labcalendar.labcalendarbackend.expense.sheets;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns the service account key into a short-lived access token (KAN-88).
 *
 * <p>This is the whole of Google's OAuth for a service account: sign a claim set with the key,
 * post it, get a token back. Pulling in the official client library to do it would add a
 * dependency tree many times the size of this file for one HTTP call, so it stays hand-written —
 * and the failure modes stay ours to name (see {@link SheetsApiException}).
 *
 * <p>The token lasts an hour; asking for a new one on every request would triple the traffic for
 * nothing. It is cached and renewed a minute early so a request never sets out with a token that
 * expires on the way.
 */
class GoogleServiceAccountTokens implements AccessTokens {

    private static final JsonMapper MAPPER = new JsonMapper();
    private static final String SCOPE = "https://www.googleapis.com/auth/spreadsheets.readonly";
    private static final String DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token";
    /** Renew this long before expiry so a token cannot die mid-request. */
    private static final long EXPIRY_MARGIN_SECONDS = 60;

    private final String clientEmail;
    private final String tokenUri;
    private final PrivateKey privateKey;
    private final RestClient http;
    private final Clock clock;

    private volatile String cachedToken;
    private volatile Instant cachedUntil = Instant.EPOCH;

    GoogleServiceAccountTokens(String credentialsJson, RestClient http, Clock clock) {
        this.http = http;
        this.clock = clock;

        Map<?, ?> key;
        try {
            key = MAPPER.readValue(credentialsJson, Map.class);
        } catch (RuntimeException malformed) {
            throw new SheetsApiException(SheetsApiException.Code.CREDENTIALS_INVALID);
        }
        this.clientEmail = text(key.get("client_email"));
        String pem = text(key.get("private_key"));
        String uri = text(key.get("token_uri"));
        this.tokenUri = uri.isBlank() ? DEFAULT_TOKEN_URI : uri;
        if (clientEmail.isBlank() || pem.isBlank()) {
            throw new SheetsApiException(SheetsApiException.Code.CREDENTIALS_INVALID);
        }
        this.privateKey = readPrivateKey(pem);
    }

    /** The account the sheet has to be shared with. Useful in a failure message. */
    String clientEmail() { return clientEmail; }

    @Override
    public synchronized String accessToken() {
        Instant now = clock.instant();
        if (cachedToken != null && now.isBefore(cachedUntil)) return cachedToken;

        String assertion = assertion(now);
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
        form.add("assertion", assertion);

        Map<?, ?> body = http.post()
                .uri(tokenUri)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .exchange((request, response) -> {
                    String raw = response.bodyTo(String.class);
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        /*
                         * 400 with invalid_grant is the usual shape of both a revoked key and a
                         * server whose clock has drifted past the token's five-minute window.
                         * Either way the operator has to go and look at the key, so they share a
                         * code; the distinction is not one this process can make.
                         */
                        throw new SheetsApiException(
                                response.getStatusCode().is5xxServerError()
                                        ? SheetsApiException.Code.TEMPORARILY_UNAVAILABLE
                                        : SheetsApiException.Code.CREDENTIALS_REJECTED);
                    }
                    try {
                        return MAPPER.readValue(raw == null ? "" : raw, Map.class);
                    } catch (RuntimeException unreadable) {
                        throw new SheetsApiException(SheetsApiException.Code.UNREADABLE_RESPONSE);
                    }
                }, false);

        String token = text(body == null ? null : body.get("access_token"));
        if (token.isBlank()) throw new SheetsApiException(SheetsApiException.Code.UNREADABLE_RESPONSE);
        long lifetime = body.get("expires_in") instanceof Number seconds ? seconds.longValue() : 3600;

        cachedToken = token;
        cachedUntil = now.plusSeconds(Math.max(1, lifetime - EXPIRY_MARGIN_SECONDS));
        return token;
    }

    /** Drops the cached token so the next call fetches a fresh one. */
    @Override
    public synchronized void forget() {
        cachedToken = null;
        cachedUntil = Instant.EPOCH;
    }

    private String assertion(Instant now) {
        long issued = now.getEpochSecond();
        var claims = new LinkedHashMap<String, Object>();
        claims.put("iss", clientEmail);
        claims.put("scope", SCOPE);
        claims.put("aud", tokenUri);
        claims.put("iat", issued);
        claims.put("exp", issued + 300);

        String head = base64(MAPPER.writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT")));
        String payload = base64(MAPPER.writeValueAsBytes(claims));
        String signingInput = head + "." + payload;

        try {
            var signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(privateKey);
            signer.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return signingInput + "." + base64(signer.sign());
        } catch (GeneralSecurityException failure) {
            throw new SheetsApiException(SheetsApiException.Code.CREDENTIALS_INVALID);
        }
    }

    private static PrivateKey readPrivateKey(String pem) {
        String body = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        try {
            byte[] der = Base64.getDecoder().decode(body);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (IllegalArgumentException | GeneralSecurityException unusable) {
            throw new SheetsApiException(SheetsApiException.Code.CREDENTIALS_INVALID);
        }
    }

    private static String base64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String text(Object value) {
        return value instanceof String string ? string : "";
    }
}
