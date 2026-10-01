package com.instagram.post;

import com.instagram.common.ApiException;
import com.instagram.common.Cursor;
import com.instagram.common.Ids;
import com.instagram.common.PageResponse;
import com.instagram.feed.FanoutEvent;
import com.instagram.feed.FeedEventPublisher;
import com.instagram.media.Media;
import com.instagram.media.MediaRepository;
import com.instagram.media.MediaStatus;
import com.instagram.media.MediaStorage;
import com.instagram.post.PostDtos.CreatePostRequest;
import com.instagram.post.PostDtos.PostResponse;
import com.instagram.user.User;
import com.instagram.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class PostService {
    private static final Logger log = LoggerFactory.getLogger(PostService.class);

    private final PostRepository posts;
    private final MediaRepository media;
    private final MediaStorage storage;
    private final UserRepository users;
    private final PostAssembler assembler;
    private final FeedEventPublisher feedEvents;
    private final Clock clock;

    public PostService(PostRepository posts, MediaRepository media, MediaStorage storage, UserRepository users,
                       PostAssembler assembler, FeedEventPublisher feedEvents, Clock clock) {
        this.posts = posts;
        this.media = media;
        this.storage = storage;
        this.users = users;
        this.assembler = assembler;
        this.feedEvents = feedEvents;
        this.clock = clock;
    }

    /**
     * Creates a post referencing already-initiated uploads. If all media is READY the post publishes
     * immediately; otherwise it stays PROCESSING and the media worker publishes it when the last item is
     * processed.
     */
    public PostResponse create(String authorId, CreatePostRequest req) {
        User author = users.findById(authorId).orElseThrow(() -> ApiException.unauthorized("Unknown user"));
        List<String> mediaIds = new ArrayList<>(new LinkedHashSet<>(req.mediaIds()));
        if (mediaIds.size() != req.mediaIds().size()) {
            throw ApiException.badRequest("DUPLICATE_MEDIA", "mediaIds must be unique");
        }

        var post = new Post();
        post.setPostId(Ids.newId());
        post.setAuthorId(authorId);
        post.setCaption(req.caption() == null ? "" : req.caption().strip());
        post.setStatus(PostStatus.PROCESSING);
        post.setMediaIds(mediaIds);
        post.setCreatedAt(clock.millis());
        posts.create(post);

        List<String> attached = new ArrayList<>();
        for (String mediaId : mediaIds) {
            if (media.attachToPost(mediaId, authorId, post.getPostId()).isEmpty()) {
                attached.forEach(id -> media.detachFromPost(id, post.getPostId()));
                posts.delete(post.getPostId());
                throw ApiException.badRequest("INVALID_MEDIA",
                        "Media " + mediaId + " does not exist, is not yours, failed processing, or is already used");
            }
            attached.add(mediaId);
        }

        // The media worker may have marked items READY before or after we attached them. Re-checking here
        // (and in the worker after it marks READY) guarantees at least one side observes "all ready".
        Post result = tryPublish(post.getPostId()).orElse(post);
        return assembler.toResponse(result, author);
    }

    /**
     * Idempotently publishes a PROCESSING post once all its media is READY (or fails it if any media
     * FAILED). Safe to call concurrently from the API and workers.
     *
     * @return the post's latest state
     */
    public Optional<Post> tryPublish(String postId) {
        Post post = posts.findById(postId, true).orElse(null);
        if (post == null) return Optional.empty();
        if (post.getStatus() != PostStatus.PROCESSING) return Optional.of(post);

        Map<String, Media> items = media.findAllByIds(post.getMediaIds());
        boolean anyFailed = items.size() != post.getMediaIds().size()
                || items.values().stream().anyMatch(m -> m.getStatus() == MediaStatus.FAILED);
        if (anyFailed) {
            post.setStatus(PostStatus.FAILED);
            posts.updateIfProcessing(post);
            return Optional.of(post);
        }
        boolean allReady = items.values().stream().allMatch(m -> m.getStatus() == MediaStatus.READY);
        if (!allReady) return Optional.of(post);

        post.setMedia(post.getMediaIds().stream().map(id -> {
            Media m = items.get(id);
            return new PostMedia(id, m.getMediaType(), m.getDisplayKey(), m.getWidth(), m.getHeight());
        }).toList());
        post.setStatus(PostStatus.PUBLISHED);
        post.setPublishedAt(clock.millis());
        if (posts.updateIfProcessing(post)) {
            users.incrementCounter(post.getAuthorId(), "postCount", 1);
            feedEvents.publish(new FanoutEvent(post.getPostId(), post.getAuthorId(), post.getPublishedAt()));
            log.info("Published post {} by {}", post.getPostId(), post.getAuthorId());
            return Optional.of(post);
        }
        // Lost the race: someone else published it. Return the winner's version.
        return posts.findById(postId, true);
    }

    public PostResponse get(String viewerId, String postId) {
        Post post = posts.findById(postId, false).orElseThrow(() -> ApiException.notFound("Post"));
        if (post.getStatus() != PostStatus.PUBLISHED && !post.getAuthorId().equals(viewerId)) {
            throw ApiException.notFound("Post");
        }
        User author = users.findById(post.getAuthorId()).orElseThrow(() -> ApiException.notFound("Post"));
        return assembler.toResponse(post, author);
    }

    public PageResponse<PostResponse> listByAuthor(String authorId, String cursorToken, int limit) {
        if (users.findById(authorId).isEmpty()) throw ApiException.notFound("User");
        Cursor cursor = Cursor.decode(cursorToken);
        List<Post> page = posts.findPublishedByAuthor(authorId, cursor, limit + 1);
        boolean hasMore = page.size() > limit;
        if (hasMore) page = page.subList(0, limit);
        String next = hasMore && !page.isEmpty()
                ? new Cursor(page.getLast().getPublishedAt(), page.getLast().getPostId()).encode() : null;
        return new PageResponse<>(assembler.toResponses(page), next);
    }

    /**
     * Deletes the post, its media and S3 objects. Cached feed entries are cleaned lazily: feed reads drop
     * ids that no longer resolve, which avoids fanning out a delete to every follower.
     */
    public void delete(String userId, String postId) {
        Post post = posts.findById(postId, true).orElseThrow(() -> ApiException.notFound("Post"));
        if (!post.getAuthorId().equals(userId)) throw ApiException.forbidden("You can only delete your own posts");
        posts.delete(postId);
        if (post.getStatus() == PostStatus.PUBLISHED) users.incrementCounter(userId, "postCount", -1);
        Map<String, Media> items = media.findAllByIds(post.getMediaIds());
        List<String> keys = new ArrayList<>();
        items.values().forEach(m -> {
            keys.add(m.getOriginalKey());
            keys.add(m.getDisplayKey());
            media.delete(m.getMediaId());
        });
        storage.deleteAll(keys);
    }
}
