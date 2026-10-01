package com.instagram.follow;

import com.instagram.follow.FollowService.FollowResult;
import com.instagram.security.AuthenticatedUser;
import com.instagram.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/follows")
@Tag(name = "Follows")
public class FollowController {
    private final FollowService followService;

    public FollowController(FollowService followService) {
        this.followService = followService;
    }

    public record FollowRequest(@NotBlank String followeeId) {}

    @PostMapping
    @Operation(summary = "Follow a user. 201 when created, 200 if already following (idempotent).")
    public ResponseEntity<FollowResult> follow(@CurrentUser AuthenticatedUser user,
                                               @Valid @RequestBody FollowRequest req) {
        FollowResult result = followService.follow(user.userId(), req.followeeId());
        return ResponseEntity.status(result.changed() ? HttpStatus.CREATED : HttpStatus.OK).body(result);
    }

    @DeleteMapping("/{followeeId}")
    @Operation(summary = "Unfollow a user (idempotent)")
    public FollowResult unfollow(@CurrentUser AuthenticatedUser user, @PathVariable String followeeId) {
        return followService.unfollow(user.userId(), followeeId);
    }
}
