package com.labcalendar.labcalendarbackend.expense.importing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import com.labcalendar.labcalendarbackend.auth.AuthProperties;

/** Signed, bounded, ten-minute token containing only digests, version and expiry, never raw names. */
@Component
public class PreviewTokenCodec {
    static final String VERSION = "ledger-v1";
    private final byte[] key;
    private final Clock clock;
    public PreviewTokenCodec(AuthProperties properties, Clock clock) {
        this.key = properties.getTokenSecret().getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }
    public String issue(String fileHash, String stateHash, String monthsHash) {
        String payload = (clock.millis() + 600_000) + "." + VERSION + "." + fileHash + "." + stateHash + "." + monthsHash;
        return payload + "." + sign(payload);
    }
    public void verify(String token, String fileHash, String stateHash, String monthsHash) {
        if (token == null || token.isBlank()) throw new ImportApiException(400, "PREVIEW_TOKEN_REQUIRED");
        if (token.length() > 1024) throw new ImportApiException(400, "PREVIEW_TOKEN_INVALID");
        String[] parts = token.split("\\.", -1);
        if (parts.length != 6) throw new ImportApiException(400, "PREVIEW_TOKEN_INVALID");
        String payload = String.join(".", java.util.Arrays.copyOf(parts, 5));
        if (!MessageDigest.isEqual(sign(payload).getBytes(StandardCharsets.US_ASCII), parts[5].getBytes(StandardCharsets.UTF_8))) {
            throw new ImportApiException(400, "PREVIEW_TOKEN_INVALID");
        }
        long expiry;
        try { expiry = Long.parseLong(parts[0]); }
        catch (NumberFormatException failure) { throw new ImportApiException(400, "PREVIEW_TOKEN_INVALID"); }
        if (clock.millis() >= expiry || !VERSION.equals(parts[1]) || !fileHash.equals(parts[2])
                || !stateHash.equals(parts[3]) || !monthsHash.equals(parts[4])) {
            throw new ImportApiException(409, "PREVIEW_STALE");
        }
    }
    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(("card-import-preview:" + payload).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException impossible) { throw new IllegalStateException("Token signing unavailable"); }
    }
    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
