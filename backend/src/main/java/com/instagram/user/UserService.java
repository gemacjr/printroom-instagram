package com.instagram.user;

import com.instagram.common.ApiException;
import com.instagram.follow.FollowService;
import com.instagram.user.UserDtos.UpdateProfileRequest;
import com.instagram.user.UserDtos.UserResponse;
import com.instagram.user.UserDtos.UserSummary;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
public class UserService {
    private final UserRepository users;
    private final FollowService follows;

    public UserService(UserRepository users, FollowService follows) {
        this.users = users;
        this.follows = follows;
    }

    public UserResponse me(String userId) {
        return UserResponse.from(load(userId), null);
    }

    public UserResponse updateMe(String userId, UpdateProfileRequest req) {
        users.updateProfile(userId,
                req.displayName() == null ? null : req.displayName().strip(),
                req.bio() == null ? null : req.bio().strip());
        return me(userId);
    }

    public UserResponse getByUsername(String viewerId, String username) {
        User user = users.findByUsername(username.toLowerCase(Locale.ROOT))
                .orElseThrow(() -> ApiException.notFound("User"));
        return UserResponse.from(user, follows.isFollowing(viewerId, user.getUserId()));
    }

    public List<UserSummary> search(String query, int limit) {
        String prefix = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (prefix.isEmpty()) return List.of();
        List<String> ids = users.searchUserIdsByUsernamePrefix(prefix, limit);
        var loaded = users.findAllByIds(ids);
        return ids.stream().map(loaded::get).filter(Objects::nonNull).map(UserSummary::from).toList();
    }

    private User load(String userId) {
        return users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
    }
}
