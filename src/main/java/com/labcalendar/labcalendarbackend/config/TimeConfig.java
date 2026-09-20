package com.labcalendar.labcalendarbackend.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeConfig {

    /** The lab's calendar day. Audit timestamps stay UTC; what counts as "today" does not. */
    public static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    /**
     * Injected wherever "today" decides a value, so the server answers the same thing to everyone.
     * A D-Day worked out on the client would shift with the device's clock and time zone.
     */
    @Bean
    public Clock serviceClock() {
        return Clock.system(SERVICE_ZONE);
    }
}
