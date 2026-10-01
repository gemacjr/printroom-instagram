package com.instagram.user;

import com.instagram.common.ApiException;
import com.instagram.security.JwtService;
import com.instagram.support.TestFixtures;
import com.instagram.user.UserDtos.LoginRequest;
import com.instagram.user.UserDtos.RegisterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {
    private UserRepository users;
    private JwtService jwt;
    private BCryptPasswordEncoder encoder;
    private AuthService auth;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        encoder = new BCryptPasswordEncoder(4); // fast rounds for tests
        jwt = new JwtService(TestFixtures.props(), TestFixtures.CLOCK);
        auth = new AuthService(users, encoder, jwt, TestFixtures.CLOCK);
    }

    @Test
    void registerNormalizesAndHashesAndReturnsToken() {
        when(users.createWithUniqueUsername(any())).thenReturn(true);

        var resp = auth.register(new RegisterRequest("alice", "Alice@Example.COM", "password123", "  Alice  "));

        var captor = ArgumentCaptor.forClass(User.class);
        verify(users).createWithUniqueUsername(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getEmail()).isEqualTo("alice@example.com");
        assertThat(saved.getDisplayName()).isEqualTo("Alice");
        assertThat(saved.getPasswordHash()).isNotEqualTo("password123");
        assertThat(encoder.matches("password123", saved.getPasswordHash())).isTrue();
        assertThat(saved.getCreatedAt()).isEqualTo(TestFixtures.NOW.toEpochMilli());
        assertThat(jwt.verify(resp.token())).get().extracting("userId").isEqualTo(saved.getUserId());
        assertThat(resp.user().username()).isEqualTo("alice");
    }

    @Test
    void registerDefaultsDisplayNameToUsername() {
        when(users.createWithUniqueUsername(any())).thenReturn(true);
        var resp = auth.register(new RegisterRequest("bob", "b@example.com", "password123", null));
        assertThat(resp.user().displayName()).isEqualTo("bob");
    }

    @Test
    void registerRejectsTakenUsername() {
        when(users.createWithUniqueUsername(any())).thenReturn(false);
        assertThatThrownBy(() -> auth.register(new RegisterRequest("alice", "a@example.com", "password123", null)))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("USERNAME_TAKEN");
    }

    @Test
    void loginSucceedsWithCorrectPasswordCaseInsensitiveUsername() {
        var user = TestFixtures.user("u1");
        user.setUsername("alice");
        user.setPasswordHash(encoder.encode("password123"));
        when(users.findByUsername("alice")).thenReturn(Optional.of(user));

        var resp = auth.login(new LoginRequest("ALICE", "password123"));

        assertThat(resp.user().userId()).isEqualTo("u1");
    }

    @Test
    void loginFailsWithWrongPassword() {
        var user = TestFixtures.user("u1");
        user.setPasswordHash(encoder.encode("password123"));
        when(users.findByUsername("alice")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> auth.login(new LoginRequest("alice", "nope")))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("UNAUTHORIZED");
    }

    @Test
    void loginFailsForUnknownUserWithSameError() {
        when(users.findByUsername("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> auth.login(new LoginRequest("ghost", "whatever")))
                .isInstanceOf(ApiException.class)
                .hasMessage("Invalid username or password");
    }
}
