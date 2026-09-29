package com.labcalendar.labcalendarbackend.auth;

import java.net.InetAddress;
import java.net.UnknownHostException;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Who a request actually came from, for counting login failures against (KAN-34).
 *
 * <p>Getting this wrong defeats the rate limit entirely: if the caller can choose their own key,
 * they can rotate it and guess forever.
 *
 * <p>{@code X-Forwarded-For} is not usable here. Our nginx appends to it
 * ({@code $proxy_add_x_forwarded_for}), so whatever the caller sent stays in front of the real
 * address and is theirs to change on every request. {@code X-Real-IP} is set to
 * {@code $remote_addr} outright, which overwrites anything the caller supplied.
 *
 * <p>That header is only worth anything when nginx is the one that set it, so it is read only when
 * the immediate peer is on a private network. A request that reaches the application directly from
 * the internet is counted by its own address and whatever headers it carries are ignored.
 */
public final class ClientAddress {

    private static final String REAL_IP_HEADER = "X-Real-IP";
    private static final String UNKNOWN = "unknown";

    private ClientAddress() {
    }

    public static String of(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        if (peer == null || peer.isBlank()) {
            return UNKNOWN;
        }
        if (!isPrivate(peer)) {
            return peer;
        }

        String forwarded = request.getHeader(REAL_IP_HEADER);
        return forwarded == null || forwarded.isBlank() ? peer : forwarded.trim();
    }

    /** A literal address, so this never resolves a name. */
    private static boolean isPrivate(String address) {
        try {
            InetAddress parsed = InetAddress.getByName(address);
            return parsed.isLoopbackAddress()
                    || parsed.isSiteLocalAddress()
                    || parsed.isAnyLocalAddress()
                    || parsed.isLinkLocalAddress();
        } catch (UnknownHostException unparseable) {
            return false;
        }
    }
}
