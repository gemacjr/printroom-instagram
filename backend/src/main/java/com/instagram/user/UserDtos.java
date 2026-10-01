package com.instagram.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class UserDtos {
    private UserDtos() {}

    public record RegisterRequest(
            @NotBlank @Size(min = 3, max = 30)
            @Pattern(regexp = "^[a-z0-9._]+$", message = "lowercase letters, digits, '.' and '_' only")
            String username,
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8, max = 100) String password,
            @Size(max = 60) String displayName) {}

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    public record AuthResponse(String token, long expiresIn, UserResponse user) {}

    public record UpdateProfileRequest(@Size(max = 60) String displayName, @Size(max = 150) String bio) {}

    /** Compact author representation embedded in posts and lists. */
    public record UserSummary(String userId, String username, String displayName) {
        public static UserSummary from(User u) {
            return new UserSummary(u.getUserId(), u.getUsername(), u.getDisplayName());
        }
    }

    public record UserResponse(
            String userId,
            String username,
            String displayName,
            String bio,
            long followerCount,
            long followingCount,
            long postCount,
            long createdAt,
            Boolean isFollowing) {

        public static UserResponse from(User u, Boolean isFollowing) {
            return new UserResponse(u.getUserId(), u.getUsername(), u.getDisplayName(), u.getBio(),
                    u.getFollowerCount(), u.getFollowingCount(), u.getPostCount(), u.getCreatedAt(), isFollowing);
        }
    }
}
