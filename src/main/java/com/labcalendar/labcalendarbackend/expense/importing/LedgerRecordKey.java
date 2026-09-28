package com.labcalendar.labcalendarbackend.expense.importing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/** v1: JSON array of normalized strings; decimal day, SHA-256, then 1-based occurrence. */
public final class LedgerRecordKey {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern SPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    public static String digest(LedgerRowParser.Entry entry) {
        var fields = List.of(Integer.toString(entry.usedOn().getDayOfMonth()), normalize(entry.cardName()),
                normalize(entry.participantNamesRaw()), normalize(entry.purpose()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(JSON.writeValueAsString(fields).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
    private static String normalize(String text) { return SPACE.matcher(text).replaceAll(" ").strip(); }
    private LedgerRecordKey() {}
}
