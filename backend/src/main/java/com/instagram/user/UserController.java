package com.instagram.user;

import com.instagram.common.Limits;
import com.instagram.common.PageResponse;
import com.instagram.follow.FollowService;
import com.instagram.post.PostDtos.PostResponse;
import com.instagram.post.PostService;
import com.instagram.security.AuthenticatedUser;
import com.instagram.security.CurrentUser;
import com.instagram.user.UserDtos.UpdateProfileRequest;
import com.instagram.user.UserDtos.UserResponse;
import com.instagram.user.UserDtos.UserSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@Tag(name = "Users")
public class UserController {
    private static final int DEFAULT_PAGE = 20;
    private static final int MAX_PAGE = 50;

    private final UserService userService;
    private final PostService postService;
    private final FollowService followService;

    public UserController(UserService userService, PostService postService, FollowService followService) {
        this.userService = userService;
        this.postService = postService;
        this.followService = followService;
    }

    @GetMapping("/me")
    public UserResponse me(@CurrentUser AuthenticatedUser user) {
        return userService.me(user.userId());
    }

    @PatchMapping("/me")
    public UserResponse updateMe(@CurrentUser AuthenticatedUser user, @Valid @RequestBody UpdateProfileRequest req) {
        return userService.updateMe(user.userId(), req);
    }

    @GetMapping("/search")
    @Operation(summary = "Username prefix search")
    public List<UserSummary> search(@RequestParam("q") String q, @RequestParam(required = false) Integer limit) {
        return userService.search(q, Limits.clamp(limit, 10, 25));
    }

    @GetMapping("/by-username/{username}")
    @Operation(summary = "Profile by username, including whether the caller follows them")
    public UserResponse byUsername(@CurrentUser AuthenticatedUser user, @PathVariable String username) {
        return userService.getByUsername(user.userId(), username);
    }

    @GetMapping("/{userId}/posts")
    public PageResponse<PostResponse> posts(@PathVariable String userId,
                                            @RequestParam(required = false) String cursor,
                                            @RequestParam(required = false) Integer limit) {
        return postService.listByAuthor(userId, cursor, Limits.clamp(limit, DEFAULT_PAGE, MAX_PAGE));
    }

    @GetMapping("/{userId}/followers")
    public PageResponse<UserSummary> followers(@PathVariable String userId,
                                               @RequestParam(required = false) String cursor,
                                               @RequestParam(required = false) Integer limit) {
        return followService.followers(userId, cursor, Limits.clamp(limit, DEFAULT_PAGE, MAX_PAGE));
    }

    @GetMapping("/{userId}/following")
    public PageResponse<UserSummary> following(@PathVariable String userId,
                                               @RequestParam(required = false) String cursor,
                                               @RequestParam(required = false) Integer limit) {
        return followService.following(userId, cursor, Limits.clamp(limit, DEFAULT_PAGE, MAX_PAGE));
    }
}
