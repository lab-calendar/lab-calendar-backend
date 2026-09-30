package com.labcalendar.labcalendarbackend.expense.sheets;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the spreadsheet over the Sheets REST API (KAN-88).
 *
 * <p>Every way this can fail is turned into a {@link SheetsApiException} with a code, because the
 * caller writes that reason into the sync history for the lab to read. A raw HTTP message is no
 * use there and can carry the document title and the caller's identity with it.
 *
 * <p>Retries cover only what a retry can fix — rate limits, Google's own 5xx, and timeouts. A 403
 * is not going to become a 200 by asking again, and retrying it would just burn the quota.
 */
class HttpSheetsClient implements SheetsClient {

    private static final JsonMapper MAPPER = new JsonMapper();
    private static final String BASE = "https://sheets.googleapis.com/v4/spreadsheets/";
    /** One request per this many tabs. Keeps a single answer small enough to hold and parse. */
    private static final int TABS_PER_REQUEST = 20;
    private static final int MAX_TOTAL_ROWS = 100_000;

    private final RestClient http;
    private final AccessTokens tokens;
    private final SheetsProperties properties;

    HttpSheetsClient(RestClient http, AccessTokens tokens, SheetsProperties properties) {
        this.http = http;
        this.tokens = tokens;
        this.properties = properties;
    }

    @Override
    public List<String> tabTitles() {
        Map<?, ?> body = withRetry(() -> get(BASE + properties.getSpreadsheetId()
                + "?fields=sheets.properties.title"));
        var titles = new ArrayList<String>();
        for (Object sheet : list(body.get("sheets"))) {
            if (!(sheet instanceof Map<?, ?> entry)) continue;
            if (!(entry.get("properties") instanceof Map<?, ?> props)) continue;
            if (props.get("title") instanceof String title) titles.add(title);
        }
        return List.copyOf(titles);
    }

    @Override
    public List<TabValues> values(List<String> titles) {
        var all = new ArrayList<TabValues>();
        int rowBudget = MAX_TOTAL_ROWS;

        for (int from = 0; from < titles.size(); from += TABS_PER_REQUEST) {
            List<String> batch = titles.subList(from, Math.min(titles.size(), from + TABS_PER_REQUEST));
            Map<?, ?> body = withRetry(() -> get(valuesUrl(batch)));
            List<?> ranges = list(body.get("valueRanges"));
            if (ranges.size() != batch.size()) {
                // Answers come back in the order they were asked for; anything else we cannot align.
                throw new SheetsApiException(SheetsApiException.Code.UNREADABLE_RESPONSE);
            }
            for (int i = 0; i < batch.size(); i++) {
                List<List<String>> rows = rowsOf(ranges.get(i));
                rowBudget -= rows.size();
                if (rowBudget < 0) throw new SheetsApiException(SheetsApiException.Code.RESPONSE_TOO_LARGE);
                all.add(new TabValues(batch.get(i), rows));
            }
        }
        return List.copyOf(all);
    }

    private String valuesUrl(List<String> titles) {
        var url = new StringBuilder(BASE).append(properties.getSpreadsheetId()).append("/values:batchGet");
        char separator = '?';
        for (String title : titles) {
            // A1 notation quotes the tab name, and an apostrophe inside it is written twice.
            String range = "'" + title.replace("'", "''") + "'!A1:D";
            url.append(separator).append("ranges=").append(encode(range));
            separator = '&';
        }
        return url.append("&majorDimension=ROWS&valueRenderOption=FORMATTED_VALUE").toString();
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private List<List<String>> rowsOf(Object valueRange) {
        if (!(valueRange instanceof Map<?, ?> range)) {
            throw new SheetsApiException(SheetsApiException.Code.UNREADABLE_RESPONSE);
        }
        var rows = new ArrayList<List<String>>();
        for (Object row : list(range.get("values"))) {
            var cells = new ArrayList<String>(4);
            for (Object cell : list(row)) cells.add(cell == null ? "" : String.valueOf(cell));
            rows.add(List.copyOf(cells));
        }
        return rows;
    }

    /** Does the call and maps the status. The body is only read on success. */
    private Map<?, ?> get(String url) {
        return http.get().uri(java.net.URI.create(url))
                .header("Authorization", "Bearer " + tokens.accessToken())
                .exchange((request, response) -> {
                    HttpStatusCode status = response.getStatusCode();
                    if (!status.is2xxSuccessful()) {
                        throw new SheetsApiException(codeFor(status.value()));
                    }
                    String raw = response.bodyTo(String.class);
                    try {
                        return MAPPER.readValue(raw == null ? "" : raw, Map.class);
                    } catch (RuntimeException unreadable) {
                        throw new SheetsApiException(SheetsApiException.Code.UNREADABLE_RESPONSE);
                    }
                }, false);
    }

    private SheetsApiException.Code codeFor(int status) {
        return switch (status) {
            /*
             * A 401 here means the token was refused even though it was issued. Forget it so the
             * next attempt asks for a new one rather than replaying the same rejected string.
             */
            case 401 -> { tokens.forget(); yield SheetsApiException.Code.CREDENTIALS_REJECTED; }
            case 403 -> SheetsApiException.Code.PERMISSION_DENIED;
            case 404 -> SheetsApiException.Code.SPREADSHEET_NOT_FOUND;
            case 429 -> SheetsApiException.Code.TEMPORARILY_UNAVAILABLE;
            default -> status >= 500
                    ? SheetsApiException.Code.TEMPORARILY_UNAVAILABLE
                    : SheetsApiException.Code.UNREADABLE_RESPONSE;
        };
    }

    private <T> T withRetry(Supplier<T> call) {
        RuntimeException last = null;
        long waitMillis = Math.max(1, properties.getRetryBackoff().toMillis());

        for (int attempt = 1; attempt <= properties.getMaxAttempts(); attempt++) {
            try {
                return call.get();
            } catch (ResourceAccessException networkFailure) {
                // Connect and read timeouts both surface here; both are worth one more try.
                last = new SheetsApiException(SheetsApiException.Code.TIMED_OUT);
            } catch (SheetsApiException failure) {
                if (!failure.retryable()) throw failure;
                last = failure;
            }
            if (attempt < properties.getMaxAttempts()) {
                sleep(waitMillis);
                waitMillis *= 2;
            }
        }
        throw last;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            // Shutting down. Give the run back rather than sleeping through it.
            Thread.currentThread().interrupt();
            throw new SheetsApiException(SheetsApiException.Code.TEMPORARILY_UNAVAILABLE);
        }
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> items ? items : List.of();
    }
}
