package com.instagram.user;

import com.instagram.common.ApiException;
import com.instagram.common.Ids;
import com.instagram.security.JwtService;
import com.instagram.user.UserDtos.AuthResponse;
import com.instagram.user.UserDtos.LoginRequest;
import com.instagram.user.UserDtos.RegisterRequest;
import com.instagram.user.UserDtos.UserResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Locale;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final Clock clock;
    /** Compared against when the user doesn't exist so login timing doesn't reveal valid usernames. */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwt, Clock clock) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.clock = clock;
        this.dummyHash = encoder.encode("timing-equalizer");
    }

    public AuthResponse register(RegisterRequest req) {
        String username = req.username().toLowerCase(Locale.ROOT);
        var user = new User();
        user.setUserId(Ids.newId());
        user.setUsername(username);
        user.setEmail(req.email().toLowerCase(Locale.ROOT));
        user.setPasswordHash(encoder.encode(req.password()));
        user.setDisplayName(req.displayName() == null || req.displayName().isBlank() ? username : req.displayName().strip());
        user.setBio("");
        user.setCreatedAt(clock.millis());
        if (!users.createWithUniqueUsername(user)) {
            throw ApiException.conflict("USERNAME_TAKEN", "Username is already taken");
        }
        return authResponse(user);
    }

    public AuthResponse login(LoginRequest req) {
        var user = users.findByUsername(req.username().toLowerCase(Locale.ROOT)).orElse(null);
        String hash = user == null ? dummyHash : user.getPasswordHash();
        boolean matches = encoder.matches(req.password(), hash);
        if (user == null || !matches) {
            throw ApiException.unauthorized("Invalid username or password");
        }
        return authResponse(user);
    }

    private AuthResponse authResponse(User user) {
        return new AuthResponse(jwt.issue(user.getUserId(), user.getUsername()), jwt.ttlSeconds(),
                UserResponse.from(user, null));
    }
}
