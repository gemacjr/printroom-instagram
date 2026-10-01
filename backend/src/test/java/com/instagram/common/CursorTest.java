package com.instagram.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CursorTest {

    @Test
    void roundTripsThroughOpaqueToken() {
        var cursor = new Cursor(1_700_000_000_000L, "post-abc:with-colon");
        var decoded = Cursor.decode(cursor.encode());
        assertThat(decoded).isEqualTo(cursor);
    }

    @Test
    void tokenIsUrlSafe() {
        assertThat(new Cursor(123, "???>>>").encode()).doesNotContain("+", "/", "=");
    }

    @Test
    void blankOrNullDecodesToNull() {
        assertThat(Cursor.decode(null)).isNull();
        assertThat(Cursor.decode("  ")).isNull();
    }

    @Test
    void malformedTokenIsBadRequest() {
        assertThatThrownBy(() -> Cursor.decode("%%%not-base64"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("INVALID_CURSOR");
        String noSeparator = java.util.Base64.getUrlEncoder().encodeToString("12345".getBytes());
        assertThatThrownBy(() -> Cursor.decode(noSeparator)).isInstanceOf(ApiException.class);
        String nonNumeric = java.util.Base64.getUrlEncoder().encodeToString("abc:id".getBytes());
        assertThatThrownBy(() -> Cursor.decode(nonNumeric)).isInstanceOf(ApiException.class);
    }

    @Test
    void isAfterUsesTimestampThenIdTieBreak() {
        var cursor = new Cursor(1000, "m");
        assertThat(cursor.isAfter(999, "z")).isTrue();   // older
        assertThat(cursor.isAfter(1001, "a")).isFalse(); // newer
        assertThat(cursor.isAfter(1000, "a")).isTrue();  // same ts, smaller id
        assertThat(cursor.isAfter(1000, "m")).isFalse(); // the cursor item itself
        assertThat(cursor.isAfter(1000, "z")).isFalse(); // same ts, larger id
    }

    @Test
    void limitsClamp() {
        assertThat(Limits.clamp(null, 20, 50)).isEqualTo(20);
        assertThat(Limits.clamp(0, 20, 50)).isEqualTo(20);
        assertThat(Limits.clamp(-5, 20, 50)).isEqualTo(20);
        assertThat(Limits.clamp(10, 20, 50)).isEqualTo(10);
        assertThat(Limits.clamp(500, 20, 50)).isEqualTo(50);
    }
}
