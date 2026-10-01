package com.instagram.security;

import com.instagram.support.TestFixtures;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {
    private final JwtService jwt = new JwtService(TestFixtures.props(), TestFixtures.CLOCK);

    @Test
    void issuedTokenVerifies() {
        String token = jwt.issue("u1", "alice");
        assertThat(jwt.verify(token)).contains(new AuthenticatedUser("u1", "alice"));
    }

    @Test
    void rejectsTamperedToken() {
        String token = jwt.issue("u1", "alice");
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("A") ? "BB" : "AA");
        assertThat(jwt.verify(tampered)).isEmpty();
        assertThat(jwt.verify("garbage")).isEmpty();
    }

    @Test
    void rejectsExpiredToken() {
        String token = jwt.issue("u1", "alice");
        var later = Clock.fixed(TestFixtures.NOW.plus(Duration.ofHours(2)), ZoneOffset.UTC);
        var futureJwt = new JwtService(TestFixtures.props(), later);
        assertThat(futureJwt.verify(token)).isEmpty();
    }

    @Test
    void rejectsTokenSignedWithDifferentKey() {
        var p = TestFixtures.props();
        var other = new JwtService(new com.instagram.config.AppProperties(
                new com.instagram.config.AppProperties.Jwt("another-secret-another-secret-another-1", Duration.ofHours(1)),
                p.aws(), p.tables(), p.media(), p.queues(), p.feed(), p.workers(), p.cors()), TestFixtures.CLOCK);
        assertThat(jwt.verify(other.issue("u1", "alice"))).isEmpty();
    }

    @Test
    void refusesShortSecrets() {
        var p = TestFixtures.props();
        var weak = new com.instagram.config.AppProperties(
                new com.instagram.config.AppProperties.Jwt("short", Duration.ofHours(1)),
                p.aws(), p.tables(), p.media(), p.queues(), p.feed(), p.workers(), p.cors());
        assertThatThrownBy(() -> new JwtService(weak, TestFixtures.CLOCK)).isInstanceOf(IllegalStateException.class);
    }
}
