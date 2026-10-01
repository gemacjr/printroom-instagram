package com.instagram.common;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Opaque keyset cursor: (timestamp, id). Timestamp alone is not unique, so the id is the tie-breaker.
 * Encoded as URL-safe base64 so clients treat it as an opaque token.
 */
public record Cursor(long timestamp, String id) {

    public static Cursor decode(String token) {
        if (token == null || token.isBlank()) return null;
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            int sep = raw.indexOf(':');
            if (sep <= 0) throw new IllegalArgumentException();
            return new Cursor(Long.parseLong(raw.substring(0, sep)), raw.substring(sep + 1));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_CURSOR", "Malformed pagination cursor");
        }
    }

    public String encode() {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((timestamp + ":" + id).getBytes(StandardCharsets.UTF_8));
    }

    /** True if an item at (ts, itemId) sorts strictly after this cursor in newest-first order. */
    public boolean isAfter(long ts, String itemId) {
        return ts < timestamp || (ts == timestamp && itemId.compareTo(id) < 0);
    }
}
