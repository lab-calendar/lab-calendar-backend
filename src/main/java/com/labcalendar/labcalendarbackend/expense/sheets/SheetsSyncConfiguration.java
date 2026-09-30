package com.labcalendar.labcalendarbackend.expense.sheets;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import com.labcalendar.labcalendarbackend.expense.importing.LedgerRowParser;
import com.labcalendar.labcalendarbackend.expense.importing.LedgerSheetSelector;
import com.labcalendar.labcalendarbackend.expense.importing.LedgerSyncService;

/**
 * Builds the sheet sync, and only when it has been switched on (KAN-88).
 *
 * <p>Conditional rather than always-on so that a deployment without a spreadsheet holds no key,
 * opens no client, and runs no timer. The ledger carries personal information; reaching for it has
 * to be a decision someone made, not a default.
 */
@Configuration
@ConditionalOnProperty(prefix = "lab-calendar.sheets", name = "enabled", havingValue = "true")
class SheetsSyncConfiguration {

    /**
     * Its own client, not the application's shared one.
     *
     * <p>The timeouts here are the difference between a slow answer from Google and a scheduled
     * run that never ends. Nothing else in the app should inherit them.
     */
    @Bean
    RestClient sheetsRestClient(SheetsProperties properties) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeout());
        factory.setReadTimeout(properties.getReadTimeout());
        return RestClient.builder().requestFactory(factory).build();
    }

    @Bean
    GoogleServiceAccountTokens googleServiceAccountTokens(SheetsProperties properties,
            RestClient sheetsRestClient, Clock clock) {
        return new GoogleServiceAccountTokens(properties.getCredentialsJson(), sheetsRestClient, clock);
    }

    @Bean
    SheetsClient sheetsClient(RestClient sheetsRestClient, AccessTokens tokens,
            SheetsProperties properties) {
        return new HttpSheetsClient(sheetsRestClient, tokens, properties);
    }

    @Bean
    SheetsLedgerReader sheetsLedgerReader(SheetsClient client, SheetsProperties properties, Clock clock) {
        return new SheetsLedgerReader(client, properties, clock);
    }

    @Bean
    SheetsSyncService sheetsSyncService(SheetsLedgerReader reader, LedgerSheetSelector selector,
            LedgerRowParser parser, LedgerSyncService sync, SheetsProperties properties,
            JdbcTemplate jdbc, Clock clock) {
        return new SheetsSyncService(reader, selector, parser, sync, properties, jdbc, clock);
    }

    @Bean
    SheetsSyncScheduler sheetsSyncScheduler(SheetsSyncService service, LedgerSyncService sync) {
        return new SheetsSyncScheduler(service, sync);
    }
}
