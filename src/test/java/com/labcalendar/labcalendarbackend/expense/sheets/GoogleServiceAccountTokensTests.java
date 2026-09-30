package com.labcalendar.labcalendarbackend.expense.sheets;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The service account handshake (KAN-88).
 *
 * <p>Signing is the part that cannot be checked by looking at it — a token endpoint would just say
 * "invalid_grant" either way. So the test verifies the assertion against the public half of the
 * key it was signed with, which is exactly what Google does with it.
 */
class GoogleServiceAccountTokensTests {

    private static final JsonMapper MAPPER = new JsonMapper();
    private static final String TOKEN_URI = "https://oauth2.googleapis.com/token";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-30T00:00:00Z"), ZoneOffset.UTC);

    private static KeyPair keys;
    private static String credentials;

    @BeforeAll
    static void makeKey() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(keys.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        credentials = MAPPER.writeValueAsString(Map.of(
                "type", "service_account",
                "client_email", "lab-calendar-reader@example.iam.gserviceaccount.com",
                "private_key", pem,
                "token_uri", TOKEN_URI));
    }

    private record Fixture(GoogleServiceAccountTokens tokens, MockRestServiceServer server) {}

    private Fixture fixture() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(new GoogleServiceAccountTokens(credentials, builder.build(), CLOCK), server);
    }

    @Test
    void signsAnAssertionGoogleCouldVerifyAndKeepsTheTokenItGetsBack() throws Exception {
        var fixture = fixture();
        var captured = new String[1];
        fixture.server().expect(requestTo(TOKEN_URI))
                .andExpect(request -> captured[0] = ((MockClientHttpRequest) request).getBodyAsString())
                .andRespond(withSuccess("{\"access_token\":\"tok-1\",\"expires_in\":3600}",
                        MediaType.APPLICATION_JSON));

        assertThat(fixture.tokens().accessToken()).isEqualTo("tok-1");

        String assertion = form(captured[0]).get("assertion");
        String[] parts = assertion.split("\\.");
        assertThat(parts).hasSize(3);

        var verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keys.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();

        Map<?, ?> claims = MAPPER.readValue(
                new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8), Map.class);
        assertThat(claims.get("iss")).isEqualTo("lab-calendar-reader@example.iam.gserviceaccount.com");
        assertThat(claims.get("aud")).isEqualTo(TOKEN_URI);
        assertThat(claims.get("scope")).isEqualTo("https://www.googleapis.com/auth/spreadsheets.readonly");
        // 5분짜리 주장 — 시계가 조금 어긋나도 구글이 받아 준다
        assertThat(((Number) claims.get("exp")).longValue() - ((Number) claims.get("iat")).longValue())
                .isEqualTo(300);
    }

    @Test
    void asksOnlyOnceWhileTheTokenIsStillGood() {
        var fixture = fixture();
        fixture.server().expect(requestTo(TOKEN_URI)).andRespond(
                withSuccess("{\"access_token\":\"tok-1\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));

        assertThat(fixture.tokens().accessToken()).isEqualTo("tok-1");
        assertThat(fixture.tokens().accessToken()).isEqualTo("tok-1");

        // 한 번만 기대해 두었으므로, 두 번 나갔다면 여기서 드러난다
        fixture.server().verify();
    }

    @Test
    void aRejectedKeyIsNamedAsSuchRatherThanForwardedRaw() {
        var fixture = fixture();
        fixture.server().expect(requestTo(TOKEN_URI)).andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
                .body("{\"error\":\"invalid_grant\",\"error_description\":\"Invalid JWT Signature.\"}")
                .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.tokens().accessToken())
                .isInstanceOf(SheetsApiException.class)
                .extracting(failure -> ((SheetsApiException) failure).code())
                .isEqualTo(SheetsApiException.Code.CREDENTIALS_REJECTED);
    }

    @Test
    void googleBeingUnwellIsWorthAnotherTry() {
        var fixture = fixture();
        fixture.server().expect(requestTo(TOKEN_URI))
                .andRespond(withStatus(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> fixture.tokens().accessToken())
                .isInstanceOf(SheetsApiException.class)
                .extracting(failure -> ((SheetsApiException) failure).retryable())
                .isEqualTo(true);
    }

    @Test
    void anUnreadableKeyIsRefusedBeforeAnythingIsSent() {
        assertThatThrownBy(() -> new GoogleServiceAccountTokens("not json at all",
                RestClient.builder().build(), CLOCK))
                .isInstanceOf(SheetsApiException.class)
                .extracting(failure -> ((SheetsApiException) failure).code())
                .isEqualTo(SheetsApiException.Code.CREDENTIALS_INVALID);

        assertThatThrownBy(() -> new GoogleServiceAccountTokens(
                "{\"client_email\":\"a@b.c\",\"private_key\":\"-----BEGIN PRIVATE KEY-----\\nZZZZ\\n-----END PRIVATE KEY-----\"}",
                RestClient.builder().build(), CLOCK))
                .isInstanceOf(SheetsApiException.class)
                .extracting(failure -> ((SheetsApiException) failure).code())
                .isEqualTo(SheetsApiException.Code.CREDENTIALS_INVALID);
    }

    private Map<String, String> form(String body) {
        var values = new java.util.HashMap<String, String>();
        for (String pair : body.split("&")) {
            int equals = pair.indexOf('=');
            values.put(URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
        }
        return values;
    }
}
