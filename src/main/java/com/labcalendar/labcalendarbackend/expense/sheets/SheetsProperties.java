package com.labcalendar.labcalendarbackend.expense.sheets;

import java.time.Duration;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Google Sheets synchronization settings (KAN-88).
 *
 * <p>Off by default. The ledger holds personal information, so a deployment that has not been
 * given a spreadsheet and a key on purpose must not start reaching out for one.
 *
 * <p>The service account key arrives as the whole JSON document through the environment. It is
 * never read from a path inside the repository — see the backend {@code .gitignore}.
 */
@Component
@ConfigurationProperties(prefix = "lab-calendar.sheets")
public class SheetsProperties {

    /** Nothing runs and nothing is contacted while this is false. */
    private boolean enabled = false;

    /** The document id from its URL. */
    private String spreadsheetId;

    /**
     * Service account key: the whole JSON, or the same JSON base64 encoded.
     *
     * <p>Base64 is there because the raw key is hostile to a {@code .env} file — it spans lines and
     * the private key body is full of characters Compose reads as variables. Encoding it makes it
     * one flat token that survives being passed through the deployment by hand.
     */
    private String credentialsJson;

    /** When the periodic run fires. Hourly on the hour by default. */
    private String cron = "0 0 * * * *";

    /**
     * How many months back to read, counting the current one.
     *
     * <p>The sheet has one tab per month and there are dozens of them. Older months do not change,
     * so reading all of them every hour would spend the quota on answers we already have.
     */
    private int monthsBack = 3;

    /** Per-request ceiling. A slow answer is better abandoned than left holding the run. */
    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration readTimeout = Duration.ofSeconds(30);

    /** How many times a retryable failure (429, 5xx, timeout) is tried before the run gives up. */
    private int maxAttempts = 3;

    /** How long to wait before the first retry. Doubles each time. */
    private Duration retryBackoff = Duration.ofSeconds(2);

    /**
     * Safety brake, absolute (KAN-89).
     *
     * <p>Removing more rows than this from a single month stops that month. Nobody is watching an
     * unattended run, so "the sheet was wiped" and "the month really is empty now" have to be told
     * apart by something other than a human noticing.
     */
    private int maxRemovalsPerMonth = 20;

    /** Safety brake, relative: the share of a month's rows the run may remove. */
    private double maxRemovalRatio = 0.5;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getSpreadsheetId() { return spreadsheetId; }
    public void setSpreadsheetId(String spreadsheetId) { this.spreadsheetId = spreadsheetId; }
    /** The key as JSON, decoding it first if it arrived base64 encoded. */
    public String getCredentialsJson() {
        if (credentialsJson == null) return null;
        String value = credentialsJson.strip();
        if (value.isEmpty() || value.startsWith("{")) return value;
        try {
            return new String(java.util.Base64.getDecoder().decode(value),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException notBase64) {
            // Leave it as it came; the key parser reports it as unreadable with its own code.
            return value;
        }
    }

    public void setCredentialsJson(String credentialsJson) { this.credentialsJson = credentialsJson; }
    public String getCron() { return cron; }
    public void setCron(String cron) { this.cron = cron; }
    public int getMonthsBack() { return monthsBack; }
    public void setMonthsBack(int monthsBack) { this.monthsBack = monthsBack; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public Duration getRetryBackoff() { return retryBackoff; }
    public void setRetryBackoff(Duration retryBackoff) { this.retryBackoff = retryBackoff; }
    public int getMaxRemovalsPerMonth() { return maxRemovalsPerMonth; }
    public void setMaxRemovalsPerMonth(int maxRemovalsPerMonth) { this.maxRemovalsPerMonth = maxRemovalsPerMonth; }
    public double getMaxRemovalRatio() { return maxRemovalRatio; }
    public void setMaxRemovalRatio(double maxRemovalRatio) { this.maxRemovalRatio = maxRemovalRatio; }

    /**
     * Refuses to start on a half-filled configuration.
     *
     * <p>A sync that is switched on but has no key would fail every hour and only say so in the
     * log. Failing at startup puts the mistake where somebody is already looking.
     */
    @PostConstruct
    void validate() {
        if (!enabled) return;

        require(spreadsheetId, "lab-calendar.sheets.spreadsheet-id");
        require(credentialsJson, "lab-calendar.sheets.credentials-json");
        if (monthsBack < 1 || monthsBack > 120) {
            throw new IllegalStateException("lab-calendar.sheets.months-back 는 1~120 사이여야 합니다.");
        }
        if (maxAttempts < 1 || maxAttempts > 10) {
            throw new IllegalStateException("lab-calendar.sheets.max-attempts 는 1~10 사이여야 합니다.");
        }
        if (maxRemovalsPerMonth < 0) {
            throw new IllegalStateException("lab-calendar.sheets.max-removals-per-month 는 0 이상이어야 합니다.");
        }
        if (maxRemovalRatio < 0 || maxRemovalRatio > 1) {
            throw new IllegalStateException("lab-calendar.sheets.max-removal-ratio 는 0~1 사이여야 합니다.");
        }
        if (positive(connectTimeout) || positive(readTimeout) || retryBackoff == null || retryBackoff.isNegative()) {
            throw new IllegalStateException("lab-calendar.sheets 의 시간 설정은 0보다 커야 합니다.");
        }
    }

    private boolean positive(Duration value) {
        return value == null || value.isZero() || value.isNegative();
    }

    private void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " 이(가) 비어 있습니다. 시트 연동을 켜려면 설정해 주세요.");
        }
    }
}
