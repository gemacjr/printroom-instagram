package com.instagram.support;

import com.instagram.config.AppProperties;
import com.instagram.media.Media;
import com.instagram.media.MediaStatus;
import com.instagram.media.MediaType;
import com.instagram.post.Post;
import com.instagram.post.PostStatus;
import com.instagram.user.User;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

public final class TestFixtures {
    private TestFixtures() {}

    public static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");
    public static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    public static AppProperties props() {
        return props(100_000);
    }

    public static AppProperties props(long celebrityThreshold) {
        return new AppProperties(
                new AppProperties.Jwt("test-secret-test-secret-test-secret-123456", Duration.ofHours(1)),
                new AppProperties.Aws("us-east-1", "http://localhost:4566", "http://localhost:4566"),
                new AppProperties.Tables("users", "usernames", "posts", "follows", "media"),
                new AppProperties.Media("bucket", "https://cdn.example.com/", Duration.ofMinutes(15),
                        8L * 1024 * 1024, 4L * 1024 * 1024 * 1024, 100L * 1024 * 1024, 100L * 1024 * 1024),
                new AppProperties.Queues("media-events", "feed-fanout"),
                new AppProperties.Feed(celebrityThreshold, 500, 100, 20, 50),
                new AppProperties.Workers(false, 1),
                new AppProperties.Cors(List.of("http://localhost:3000")));
    }

    public static User user(String id) {
        return user(id, 0);
    }

    public static User user(String id, long followers) {
        var u = new User();
        u.setUserId(id);
        u.setUsername("user_" + id);
        u.setDisplayName("User " + id);
        u.setEmail(id + "@example.com");
        u.setFollowerCount(followers);
        u.setCreatedAt(NOW.toEpochMilli());
        return u;
    }

    public static Media media(String id, String owner, MediaStatus status) {
        var m = new Media();
        m.setMediaId(id);
        m.setOwnerId(owner);
        m.setMediaType(MediaType.IMAGE);
        m.setContentType("image/jpeg");
        m.setSizeBytes(1000);
        m.setOriginalKey(Media.originalKeyFor(owner, id));
        m.setStatus(status);
        if (status == MediaStatus.READY) {
            m.setDisplayKey("media/" + owner + "/" + id + "/display.jpg");
            m.setWidth(1080);
            m.setHeight(1080);
        }
        return m;
    }

    public static Post publishedPost(String id, String author, long publishedAt) {
        var p = new Post();
        p.setPostId(id);
        p.setAuthorId(author);
        p.setCaption("caption " + id);
        p.setStatus(PostStatus.PUBLISHED);
        p.setMediaIds(List.of());
        p.setMedia(List.of());
        p.setCreatedAt(publishedAt);
        p.setPublishedAt(publishedAt);
        return p;
    }
}
