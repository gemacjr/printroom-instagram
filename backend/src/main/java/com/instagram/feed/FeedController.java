package com.instagram.feed;

import com.instagram.common.PageResponse;
import com.instagram.post.PostDtos.PostResponse;
import com.instagram.security.AuthenticatedUser;
import com.instagram.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/feed")
@Tag(name = "Feed")
public class FeedController {
    private final FeedService feedService;

    public FeedController(FeedService feedService) {
        this.feedService = feedService;
    }

    @GetMapping
    @Operation(summary = "Chronological feed of followed users' posts (cursor paginated)")
    public PageResponse<PostResponse> feed(@CurrentUser AuthenticatedUser user,
                                           @RequestParam(required = false) String cursor,
                                           @RequestParam(required = false) Integer limit) {
        return feedService.getFeed(user.userId(), cursor, limit);
    }
}
