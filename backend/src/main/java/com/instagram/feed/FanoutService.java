package com.instagram.feed;

import com.instagram.config.AppProperties;
import com.instagram.follow.FollowRepository;
import com.instagram.user.User;
import com.instagram.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fan-out-on-write, executed by the feed worker off the request path.
 * Celebrity authors (followers >= threshold) are skipped: writing one post into 100M+ feeds would take
 * minutes and hammer Redis, so their posts are merged in at read time instead.
 */
@Service
public class FanoutService {
    private static final Logger log = LoggerFactory.getLogger(FanoutService.class);

    private final UserRepository users;
    private final FollowRepository follows;
    private final FeedCache cache;
    private final AppProperties.Feed config;

    public FanoutService(UserRepository users, FollowRepository follows, FeedCache cache, AppProperties props) {
        this.users = users;
        this.follows = follows;
        this.cache = cache;
        this.config = props.feed();
    }

    /** @return number of follower feeds the post was offered to (0 for celebrities). */
    public int fanout(FanoutEvent event) {
        User author = users.findById(event.authorId()).orElse(null);
        if (author == null) {
            log.warn("Skipping fan-out for post {}: author {} missing", event.postId(), event.authorId());
            return 0;
        }
        var entry = new FeedEntry(event.postId(), event.publishedAt());
        // Authors always see their own posts.
        cache.addToExistingFeeds(List.of(author.getUserId()), entry);

        boolean celebrity = isCelebrity(author);
        cache.setCelebrity(author.getUserId(), celebrity);
        if (celebrity) {
            log.info("Post {} by celebrity {} ({} followers): skipping fan-out",
                    event.postId(), author.getUserId(), author.getFollowerCount());
            return 0;
        }

        AtomicInteger offered = new AtomicInteger();
        follows.forEachFollowerBatch(author.getUserId(), config.fanoutBatchSize(), batch -> {
            cache.addToExistingFeeds(batch, entry);
            offered.addAndGet(batch.size());
        });
        log.info("Fanned out post {} to {} followers", event.postId(), offered.get());
        return offered.get();
    }

    public boolean isCelebrity(User user) {
        return user.getFollowerCount() >= config.celebrityThreshold();
    }
}
