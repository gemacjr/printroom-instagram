package com.instagram.feed;

import com.instagram.common.Cursor;
import com.instagram.common.Limits;
import com.instagram.common.PageResponse;
import com.instagram.config.AppProperties;
import com.instagram.follow.FollowRepository;
import com.instagram.post.Post;
import com.instagram.post.PostAssembler;
import com.instagram.post.PostDtos.PostResponse;
import com.instagram.post.PostRepository;
import com.instagram.post.PostStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * Hybrid feed read path:
 * <ol>
 *   <li>Precomputed entries from the user's Redis feed (authors fanned out on write).</li>
 *   <li>Recent posts from followed celebrities, queried at read time from the author GSI.</li>
 *   <li>Merge, de-dupe, take one page, then hydrate posts + authors in batch.</li>
 * </ol>
 * If the user's feed isn't materialized (new user, evicted after inactivity, Redis failover) it is rebuilt
 * from DynamoDB first — fan-out-on-read as a fallback.
 */
@Service
public class FeedService {
    private static final Logger log = LoggerFactory.getLogger(FeedService.class);
    static final int REBUILD_MAX_PER_AUTHOR = 20;

    private final FeedCache cache;
    private final FollowRepository follows;
    private final PostRepository posts;
    private final PostAssembler assembler;
    private final AppProperties.Feed config;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public FeedService(FeedCache cache, FollowRepository follows, PostRepository posts,
                       PostAssembler assembler, AppProperties props) {
        this.cache = cache;
        this.follows = follows;
        this.posts = posts;
        this.assembler = assembler;
        this.config = props.feed();
    }

    public PageResponse<PostResponse> getFeed(String userId, String cursorToken, Integer requestedLimit) {
        int limit = Limits.clamp(requestedLimit, config.defaultPageSize(), config.maxPageSize());
        Cursor cursor = Cursor.decode(cursorToken);

        List<String> followees = follows.findAllFolloweeIds(userId);
        Set<String> celebrities = cache.filterCelebrities(followees);

        if (!cache.exists(userId)) {
            rebuild(userId, followees.stream().filter(id -> !celebrities.contains(id)).toList());
        }

        // Fetch limit+1 from every source: if the merged result exceeds limit, there is a next page.
        List<List<FeedEntry>> sources = new ArrayList<>();
        sources.add(cache.page(userId, cursor, limit + 1));
        sources.addAll(parallel(celebrities, id -> toEntries(posts.findPublishedByAuthor(id, cursor, limit + 1))));

        List<FeedEntry> window = FeedMerger.merge(sources, limit + 1);
        boolean hasMore = window.size() > limit;
        if (hasMore) window = window.subList(0, limit);

        Map<String, Post> loaded = posts.findAllByIds(window.stream().map(FeedEntry::postId).toList());
        List<Post> ordered = window.stream()
                .map(e -> loaded.get(e.postId()))
                // Deleted posts linger in cached feeds until trimmed; drop them here (lazy delete).
                .filter(p -> p != null && p.getStatus() == PostStatus.PUBLISHED)
                .toList();

        String next = null;
        if (hasMore) {
            FeedEntry last = window.getLast();
            next = new Cursor(last.publishedAt(), last.postId()).encode();
        }
        return new PageResponse<>(assembler.toResponses(ordered), next);
    }

    /** Fan-out-on-read: materializes the feed from followed (non-celebrity) authors and the user's own posts. */
    void rebuild(String userId, List<String> nonCelebrityFollowees) {
        List<String> authors = new ArrayList<>(nonCelebrityFollowees);
        authors.add(userId);
        int perAuthor = Math.max(3, Math.min(REBUILD_MAX_PER_AUTHOR, config.maxCachedEntries() / authors.size()));
        List<List<FeedEntry>> perAuthorEntries =
                parallel(authors, id -> toEntries(posts.findPublishedByAuthor(id, null, perAuthor)));
        List<FeedEntry> merged = FeedMerger.merge(perAuthorEntries, config.maxCachedEntries());
        cache.initialize(userId, merged);
        log.debug("Rebuilt feed for {} from {} authors ({} entries)", userId, authors.size(), merged.size());
    }

    private <T> List<List<FeedEntry>> parallel(java.util.Collection<T> inputs, Function<T, List<FeedEntry>> fn) {
        var futures = inputs.stream()
                .map(in -> CompletableFuture.supplyAsync(() -> fn.apply(in), executor))
                .toList();
        return futures.stream().map(CompletableFuture::join).filter(Objects::nonNull).toList();
    }

    static List<FeedEntry> toEntries(List<Post> list) {
        return list.stream().map(p -> new FeedEntry(p.getPostId(), p.getPublishedAt())).toList();
    }
}
