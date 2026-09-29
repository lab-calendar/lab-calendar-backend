package com.labcalendar.labcalendarbackend.category.dto;

/**
 * One category as the filter and the calendar use it (docs/api-contract.md §5).
 *
 * <p>No colour. The frontend keeps those in its design tokens, chosen against a 4.5:1 contrast
 * floor with a regression test behind them (KAN-64) — sending a colour from here would let the
 * database quietly undo that.
 *
 * <p>{@code key} is the database {@code code}. It is a hard contract: the frontend keys its
 * {@code [data-category]} selectors and its URL filter parameter on this exact string.
 */
public record CategoryResponse(String id, String key, String name) {
}
