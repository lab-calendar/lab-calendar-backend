package com.labcalendar.labcalendarbackend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled} (KAN-49).
 *
 * <p>Its own class rather than an annotation on the application, so a test slice can leave the
 * batch out by not importing it.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
