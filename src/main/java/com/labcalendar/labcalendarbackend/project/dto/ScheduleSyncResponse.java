package com.labcalendar.labcalendarbackend.project.dto;

/**
 * What a manual reconcile of the preparation schedules did (KAN-49).
 *
 * <p>Counts rather than a bare 204 so whoever triggered it can see whether anything was actually
 * out of step — "0 created, 0 moved" is the answer you want from a healthy system.
 */
public record ScheduleSyncResponse(int created, int moved, int projects) {
}
