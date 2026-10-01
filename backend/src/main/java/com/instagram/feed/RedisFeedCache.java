package com.instagram.feed;

import com.instagram.common.Cursor;
import com.instagram.config.AppProperties;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Redis layout:
 * <pre>
 *   feed:{userId}   ZSET  member=postId score=publishedAt   (+ sentinel member "~" at score 0)
 *   celebrities     SET   userIds whose follower count >= threshold
 * </pre>
 * The sentinel distinguishes "materialized but empty" from "never built / evicted". Feeds expire after
 * {@link #FEED_TTL} of inactivity so memory is spent on active users only.
 */
@Component
public class RedisFeedCache implements FeedCache {
    static final String SENTINEL = "~";
    static final String CELEBRITIES_KEY = "celebrities";
    static final Duration FEED_TTL = Duration.ofDays(7);

    private final StringRedisTemplate redis;
    private final int maxEntries;

    public RedisFeedCache(StringRedisTemplate redis, AppProperties props) {
        this.redis = redis;
        this.maxEntries = props.feed().maxCachedEntries();
    }

    static String feedKey(String userId) {
        return "feed:" + userId;
    }

    @Override
    public boolean exists(String userId) {
        return Boolean.TRUE.equals(redis.hasKey(feedKey(userId)));
    }

    @Override
    public void initialize(String userId, List<FeedEntry> entries) {
        String key = feedKey(userId);
        redis.executePipelined((RedisCallback<Object>) conn -> {
            var c = (StringRedisConnection) conn;
            c.del(key);
            c.zAdd(key, 0, SENTINEL);
            for (FeedEntry e : entries) c.zAdd(key, e.publishedAt(), e.postId());
            trim(c, key);
            c.expire(key, FEED_TTL.toSeconds());
            return null;
        });
    }

    @Override
    public void addToExistingFeeds(Collection<String> userIds, FeedEntry entry) {
        List<String> ids = new ArrayList<>(userIds);
        // Round trip 1: which feeds are materialized?
        List<Object> existsResults = redis.executePipelined((RedisCallback<Object>) conn -> {
            var c = (StringRedisConnection) conn;
            ids.forEach(id -> c.exists(feedKey(id)));
            return null;
        });
        List<String> live = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            if (Boolean.TRUE.equals(existsResults.get(i))) live.add(ids.get(i));
        }
        if (live.isEmpty()) return;
        // Round trip 2: ZADD + trim for each live feed.
        redis.executePipelined((RedisCallback<Object>) conn -> {
            var c = (StringRedisConnection) conn;
            for (String id : live) {
                c.zAdd(feedKey(id), entry.publishedAt(), entry.postId());
                trim(c, feedKey(id));
            }
            return null;
        });
    }

    @Override
    public void addAll(String userId, List<FeedEntry> entries) {
        if (entries.isEmpty() || !exists(userId)) return;
        String key = feedKey(userId);
        redis.executePipelined((RedisCallback<Object>) conn -> {
            var c = (StringRedisConnection) conn;
            for (FeedEntry e : entries) c.zAdd(key, e.publishedAt(), e.postId());
            trim(c, key);
            return null;
        });
    }

    @Override
    public void removeAll(String userId, Collection<String> postIds) {
        if (postIds.isEmpty()) return;
        redis.opsForZSet().remove(feedKey(userId), postIds.toArray());
    }

    @Override
    public List<FeedEntry> page(String userId, Cursor before, int count) {
        String key = feedKey(userId);
        // Scores are inclusive of the cursor timestamp; same-timestamp entries are filtered by postId.
        double max = before == null ? (double) Long.MAX_VALUE : before.timestamp();
        // Over-fetch slightly to absorb same-timestamp entries that are filtered out.
        int fetch = count + 10;
        Set<TypedTuple<String>> tuples = redis.opsForZSet().reverseRangeByScoreWithScores(key, 1.0, max, 0, fetch);
        redis.expire(key, FEED_TTL);
        List<FeedEntry> out = new ArrayList<>();
        if (tuples == null) return out;
        for (var t : tuples) {
            if (SENTINEL.equals(t.getValue()) || t.getScore() == null) continue;
            var e = new FeedEntry(t.getValue(), t.getScore().longValue());
            if (before != null && !before.isAfter(e.publishedAt(), e.postId())) continue;
            out.add(e);
        }
        out.sort(FeedEntry.NEWEST_FIRST);
        return out.size() > count ? out.subList(0, count) : out;
    }

    @Override
    public boolean isCelebrity(String userId) {
        return Boolean.TRUE.equals(redis.opsForSet().isMember(CELEBRITIES_KEY, userId));
    }

    @Override
    public Set<String> filterCelebrities(Collection<String> userIds) {
        if (userIds.isEmpty()) return Set.of();
        var ids = new ArrayList<>(userIds);
        var flags = redis.opsForSet().isMember(CELEBRITIES_KEY, ids.toArray());
        Set<String> celebs = new HashSet<>();
        if (flags == null) return celebs;
        flags.forEach((id, isMember) -> {
            if (Boolean.TRUE.equals(isMember)) celebs.add(Objects.toString(id));
        });
        return celebs;
    }

    @Override
    public void setCelebrity(String userId, boolean celebrity) {
        if (celebrity) redis.opsForSet().add(CELEBRITIES_KEY, userId);
        else redis.opsForSet().remove(CELEBRITIES_KEY, userId);
    }

    /** Keep the newest maxEntries posts plus the sentinel (rank 0, score 0). */
    private void trim(StringRedisConnection c, String key) {
        c.zRemRange(key, 1, -(maxEntries + 2));
    }
}
