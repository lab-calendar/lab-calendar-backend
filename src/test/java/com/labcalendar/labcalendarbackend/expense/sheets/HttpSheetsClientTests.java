package com.labcalendar.labcalendarbackend.expense.sheets;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * How Google's answers are turned into reasons (KAN-89).
 *
 * <p>Each status means something different to whoever has to fix it — a 403 is a sharing setting,
 * a 404 is the wrong id, a 429 is just today's quota. Collapsing them into "sync failed" would
 * leave the lab guessing, so the mapping is worth pinning down.
 */
class HttpSheetsClientTests {

    private final AtomicInteger forgotten = new AtomicInteger();

    private final AccessTokens tokens = new AccessTokens() {
        @Override public String accessToken() { return "tok-1"; }
        @Override public void forget() { forgotten.incrementAndGet(); }
    };

    private SheetsProperties properties(int attempts) {
        var properties = new SheetsProperties();
        properties.setSpreadsheetId("sheet-1");
        properties.setMaxAttempts(attempts);
        properties.setRetryBackoff(Duration.ofMillis(1));
        return properties;
    }

    private record Fixture(HttpSheetsClient client, MockRestServiceServer server) {}

    private Fixture fixture(int attempts) {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        return new Fixture(new HttpSheetsClient(builder.build(), tokens, properties(attempts)), server);
    }

    private SheetsApiException.Code codeOf(Throwable failure) {
        return ((SheetsApiException) failure).code();
    }

    @Test
    void readsTitlesAndValuesAndCarriesTheBearerToken() {
        var fixture = fixture(1);
        fixture.server().expect(anything())
                .andExpect(header("Authorization", "Bearer tok-1"))
                .andRespond(withSuccess("""
                        {"sheets":[{"properties":{"title":"2026년 9월"}},{"properties":{"title":"복사용 시트"}}]}
                        """, MediaType.APPLICATION_JSON));
        fixture.server().expect(anything()).andRespond(withSuccess("""
                {"valueRanges":[{"range":"'2026년 9월'!A1:D","values":[["1","과제","홍길동","점심"],["2"]]}]}
                """, MediaType.APPLICATION_JSON));

        assertThat(fixture.client().tabTitles()).containsExactly("2026년 9월", "복사용 시트");

        List<SheetsClient.TabValues> values = fixture.client().values(List.of("2026년 9월"));
        assertThat(values).hasSize(1);
        assertThat(values.get(0).rows()).containsExactly(
                List.of("1", "과제", "홍길동", "점심"), List.of("2"));
    }

    @Test
    void sharingWithdrawnIsNotRetried() {
        var fixture = fixture(3);
        // 세 번을 허용해 두었지만, 403 은 다시 물어도 답이 같다
        fixture.server().expect(ExpectedCount.once(), anything())
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> fixture.client().tabTitles())
                .isInstanceOf(SheetsApiException.class)
                .satisfies(failure -> assertThat(codeOf(failure))
                        .isEqualTo(SheetsApiException.Code.PERMISSION_DENIED));
        fixture.server().verify();
    }

    @Test
    void aMissingDocumentIsItsOwnReason() {
        var fixture = fixture(1);
        fixture.server().expect(anything()).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> fixture.client().tabTitles())
                .satisfies(failure -> assertThat(codeOf(failure))
                        .isEqualTo(SheetsApiException.Code.SPREADSHEET_NOT_FOUND));
    }

    @Test
    void aRateLimitIsTriedAgainBeforeGivingUp() {
        var fixture = fixture(3);
        fixture.server().expect(ExpectedCount.times(3), anything())
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> fixture.client().tabTitles())
                .satisfies(failure -> assertThat(codeOf(failure))
                        .isEqualTo(SheetsApiException.Code.TEMPORARILY_UNAVAILABLE));
        fixture.server().verify();
    }

    @Test
    void aRetryThatSucceedsIsNotAFailure() {
        var fixture = fixture(3);
        fixture.server().expect(ExpectedCount.once(), anything())
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        fixture.server().expect(ExpectedCount.once(), anything()).andRespond(withSuccess(
                "{\"sheets\":[{\"properties\":{\"title\":\"2026년 9월\"}}]}", MediaType.APPLICATION_JSON));

        assertThat(fixture.client().tabTitles()).containsExactly("2026년 9월");
        fixture.server().verify();
    }

    @Test
    void aRefusedTokenIsDroppedSoTheNextTryAsksForANewOne() {
        var fixture = fixture(1);
        fixture.server().expect(anything()).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> fixture.client().tabTitles())
                .satisfies(failure -> assertThat(codeOf(failure))
                        .isEqualTo(SheetsApiException.Code.CREDENTIALS_REJECTED));
        assertThat(forgotten).hasValue(1);
    }

    @Test
    void anAnswerThatIsNotTheDocumentedShapeIsRefused() {
        var fixture = fixture(1);
        fixture.server().expect(anything()).andRespond(withSuccess("not json", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client().tabTitles())
                .satisfies(failure -> assertThat(codeOf(failure))
                        .isEqualTo(SheetsApiException.Code.UNREADABLE_RESPONSE));
    }

    @Test
    void valuesThatDoNotLineUpWithTheRequestAreRefusedRatherThanGuessedAt() {
        var fixture = fixture(1);
        // 두 탭을 물었는데 하나만 왔다 — 어느 탭의 답인지 맞힐 수 없다
        fixture.server().expect(anything()).andRespond(withSuccess(
                "{\"valueRanges\":[{\"range\":\"'2026년 9월'!A1:D\",\"values\":[]}]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client().values(List.of("2026년 9월", "2026년 8월")))
                .satisfies(failure -> assertThat(codeOf(failure))
                        .isEqualTo(SheetsApiException.Code.UNREADABLE_RESPONSE));
    }
}
