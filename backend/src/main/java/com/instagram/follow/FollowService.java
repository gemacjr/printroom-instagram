package com.instagram.follow;

import com.instagram.common.ApiException;
import com.instagram.common.Cursor;
import com.instagram.common.PageResponse;
import com.instagram.feed.FanoutService;
import com.instagram.feed.FeedCache;
import com.instagram.feed.FeedEntry;
import com.instagram.post.Post;
import com.instagram.post.PostRepository;
import com.instagram.user.User;
import com.instagram.user.UserDtos.UserSummary;
import com.instagram.user.UserRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class FollowService {
    /** Recent posts pulled into a follower's feed immediately on follow, so the feed isn't empty. */
    static final int BACKFILL_POSTS = 20;

    private final FollowRepository follows;
    private final UserRepository users;
    private final PostRepository posts;
    private final FeedCache cache;
    private final FanoutService fanout;
    private final Clock clock;

    public FollowService(FollowRepository follows, UserRepository users, PostRepository posts,
                         FeedCache cache, FanoutService fanout, Clock clock) {
        this.follows = follows;
        this.users = users;
        this.posts = posts;
        this.cache = cache;
        this.fanout = fanout;
        this.clock = clock;
    }

    public record FollowResult(String followeeId, boolean following, boolean changed) {}

    public FollowResult follow(String followerId, String followeeId) {
        if (followerId.equals(followeeId)) {
            throw ApiException.badRequest("CANNOT_FOLLOW_SELF", "You cannot follow yourself");
        }
        if (users.findById(followeeId).isEmpty()) throw ApiException.notFound("User");
        boolean created;
        try {
            created = follows.create(followerId, followeeId, clock.millis());
        } catch (IllegalStateException e) {
            throw ApiException.notFound("User");
        }
        if (created) {
            User followee = users.findById(followeeId).orElseThrow();
            boolean celebrity = syncCelebrity(followee);
            if (!celebrity) {
                // Celebrity posts are merged at read time, so only backfill regular authors.
                List<FeedEntry> recent = posts.findPublishedByAuthor(followeeId, null, BACKFILL_POSTS).stream()
                        .map(p -> new FeedEntry(p.getPostId(), p.getPublishedAt())).toList();
                cache.addAll(followerId, recent);
            }
        }
        return new FollowResult(followeeId, true, created);
    }

    public FollowResult unfollow(String followerId, String followeeId) {
        boolean deleted = follows.delete(followerId, followeeId);
        if (deleted) {
            users.findById(followeeId).ifPresent(this::syncCelebrity);
            List<String> ids = posts.findPublishedByAuthor(followeeId, null, 500).stream()
                    .map(Post::getPostId).toList();
            cache.removeAll(followerId, ids);
        }
        return new FollowResult(followeeId, false, deleted);
    }

    public boolean isFollowing(String followerId, String followeeId) {
        return !followerId.equals(followeeId) && follows.exists(followerId, followeeId);
    }

    public PageResponse<UserSummary> followers(String userId, String cursorToken, int limit) {
        Cursor cursor = Cursor.decode(cursorToken);
        var page = follows.findFollowers(userId, cursor == null ? null : cursor.id(), limit);
        return toPage(page);
    }

    public PageResponse<UserSummary> following(String userId, String cursorToken, int limit) {
        Cursor cursor = Cursor.decode(cursorToken);
        var page = follows.findFollowees(userId, cursor == null ? null : cursor.id(), limit);
        return toPage(page);
    }

    private PageResponse<UserSummary> toPage(FollowRepository.FollowPage page) {
        Map<String, User> loaded = users.findAllByIds(page.userIds());
        List<UserSummary> items = page.userIds().stream()
                .map(loaded::get).filter(Objects::nonNull).map(UserSummary::from).toList();
        String next = page.lastKey() == null ? null : new Cursor(0, page.lastKey()).encode();
        return new PageResponse<>(items, next);
    }

    /** Keeps the Redis celebrity registry in sync with the authoritative follower count. */
    private boolean syncCelebrity(User user) {
        boolean celebrity = fanout.isCelebrity(user);
        cache.setCelebrity(user.getUserId(), celebrity);
        return celebrity;
    }
}
