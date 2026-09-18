package com.labcalendar.labcalendarbackend.member.dto;

/**
 * One researcher in the lab roster (KAN-41).
 *
 * <p>This is a name list, not a login. Access is the shared password (KAN-21); a member row says
 * who can be picked as an owner or an attendee.
 */
public record MemberResponse(String id, String name, boolean active) {
}
