package com.instagram.post;

import com.instagram.common.ApiException;
import com.instagram.common.Cursor;
import com.instagram.feed.FanoutEvent;
import com.instagram.feed.FeedEventPublisher;
import com.instagram.media.Media;
import com.instagram.media.MediaRepository;
import com.instagram.media.MediaStatus;
import com.instagram.media.MediaStorage;
import com.instagram.media.MediaUrls;
import com.instagram.post.PostDtos.CreatePostRequest;
import com.instagram.support.TestFixtures;
import com.instagram.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostServiceTest {
    private PostRepository posts;
    private MediaRepository media;
    private MediaStorage storage;
    private UserRepository users;
    private FeedEventPublisher events;
    private PostService service;

    /** In-memory stand-ins so create() -> tryPublish() round-trips behave like the real tables. */
    private final Map<String, Post> postStore = new HashMap<>();
    private final Map<String, Media> mediaStore = new HashMap<>();

    @BeforeEach
    void setUp() {
        posts = mock(PostRepository.class);
        media = mock(MediaRepository.class);
        storage = mock(MediaStorage.class);
        users = mock(UserRepository.class);
        events = mock(FeedEventPublisher.class);
        var props = TestFixtures.props();
        var assembler = new PostAssembler(users, new MediaUrls(props));
        service = new PostService(posts, media, storage, users, assembler, events, TestFixtures.CLOCK);

        when(users.findById("author")).thenReturn(Optional.of(TestFixtures.user("author")));
        when(users.findAllByIds(anyCollection())).thenReturn(Map.of("author", TestFixtures.user("author")));

        org.mockito.Mockito.doAnswer(inv -> {
            Post p = inv.getArgument(0);
            postStore.put(p.getPostId(), p);
            return null;
        }).when(posts).create(any());
        when(posts.findById(anyString(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenAnswer(inv -> Optional.ofNullable(postStore.get((String) inv.getArgument(0))));
        when(posts.updateIfProcessing(any())).thenAnswer(inv -> {
            Post p = inv.getArgument(0);
            postStore.put(p.getPostId(), p);
            return true;
        });
        when(media.attachToPost(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            Media m = mediaStore.get((String) inv.getArgument(0));
            if (m == null || !m.getOwnerId().equals(inv.getArgument(1)) || m.getPostId() != null) return Optional.empty();
            m.setPostId(inv.getArgument(2));
            return Optional.of(m);
        });
        when(media.findAllByIds(anyCollection())).thenAnswer(inv -> {
            Map<String, Media> out = new HashMap<>();
            for (Object id : (java.util.Collection<?>) inv.getArgument(0)) {
                Media m = mediaStore.get((String) id);
                if (m != null) out.put((String) id, m);
            }
            return out;
        });
    }

    private void storeMedia(String id, MediaStatus status) {
        mediaStore.put(id, TestFixtures.media(id, "author", status));
    }

    @Test
    void publishesImmediatelyWhenAllMediaReady() {
        storeMedia("m1", MediaStatus.READY);
        storeMedia("m2", MediaStatus.READY);

        var resp = service.create("author", new CreatePostRequest("  hello  ", List.of("m1", "m2")));

        assertThat(resp.status()).isEqualTo(PostStatus.PUBLISHED);
        assertThat(resp.caption()).isEqualTo("hello");
        assertThat(resp.publishedAt()).isEqualTo(TestFixtures.NOW.toEpochMilli());
        assertThat(resp.media()).extracting(PostDtos.MediaItem::url).containsExactly(
                "https://cdn.example.com/media/author/m1/display.jpg",
                "https://cdn.example.com/media/author/m2/display.jpg");
        verify(users).incrementCounter("author", "postCount", 1);
        var captor = ArgumentCaptor.forClass(FanoutEvent.class);
        verify(events).publish(captor.capture());
        assertThat(captor.getValue().authorId()).isEqualTo("author");
        assertThat(captor.getValue().postId()).isEqualTo(resp.postId());
    }

    @Test
    void staysProcessingUntilMediaReadyThenWorkerPublishes() {
        storeMedia("m1", MediaStatus.READY);
        storeMedia("m2", MediaStatus.PENDING_UPLOAD);

        var resp = service.create("author", new CreatePostRequest("hi", List.of("m1", "m2")));

        assertThat(resp.status()).isEqualTo(PostStatus.PROCESSING);
        assertThat(resp.publishedAt()).isNull();
        verify(events, never()).publish(any());

        // Worker marks m2 ready and nudges the post.
        Media m2 = mediaStore.get("m2");
        m2.setStatus(MediaStatus.READY);
        m2.setDisplayKey("k2");
        var published = service.tryPublish(resp.postId()).orElseThrow();

        assertThat(published.getStatus()).isEqualTo(PostStatus.PUBLISHED);
        assertThat(published.getMedia()).extracting(PostMedia::getMediaId).containsExactly("m1", "m2");
        verify(events).publish(any());
    }

    @Test
    void failsPostWhenAnyMediaFailed() {
        storeMedia("m1", MediaStatus.PENDING_UPLOAD);
        var resp = service.create("author", new CreatePostRequest("hi", List.of("m1")));

        mediaStore.get("m1").setStatus(MediaStatus.FAILED);
        var result = service.tryPublish(resp.postId()).orElseThrow();

        assertThat(result.getStatus()).isEqualTo(PostStatus.FAILED);
        verify(events, never()).publish(any());
    }

    @Test
    void tryPublishIsIdempotentWhenAlreadyPublished() {
        storeMedia("m1", MediaStatus.READY);
        var resp = service.create("author", new CreatePostRequest("hi", List.of("m1")));

        service.tryPublish(resp.postId());
        service.tryPublish(resp.postId());

        verify(events, org.mockito.Mockito.times(1)).publish(any());
    }

    @Test
    void losingThePublishRaceDoesNotDoubleFanout() {
        storeMedia("m1", MediaStatus.PENDING_UPLOAD);
        var resp = service.create("author", new CreatePostRequest("hi", List.of("m1")));
        mediaStore.get("m1").setStatus(MediaStatus.READY);
        org.mockito.Mockito.doReturn(false).when(posts).updateIfProcessing(any());

        service.tryPublish(resp.postId());

        verify(events, never()).publish(any());
        verify(users, never()).incrementCounter(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void rollsBackWhenMediaCannotBeAttached() {
        storeMedia("m1", MediaStatus.READY);
        mediaStore.put("theirs", TestFixtures.media("theirs", "someone-else", MediaStatus.READY));

        assertThatThrownBy(() -> service.create("author", new CreatePostRequest("hi", List.of("m1", "theirs"))))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("INVALID_MEDIA");

        verify(media).detachFromPost(eq("m1"), anyString());
        verify(posts).delete(anyString());
    }

    @Test
    void rejectsDuplicateMediaIds() {
        assertThatThrownBy(() -> service.create("author", new CreatePostRequest("hi", List.of("m1", "m1"))))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("DUPLICATE_MEDIA");
    }

    @Test
    void processingPostsAreVisibleOnlyToAuthor() {
        storeMedia("m1", MediaStatus.PENDING_UPLOAD);
        var resp = service.create("author", new CreatePostRequest("hi", List.of("m1")));

        assertThat(service.get("author", resp.postId()).status()).isEqualTo(PostStatus.PROCESSING);
        assertThatThrownBy(() -> service.get("stranger", resp.postId()))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("NOT_FOUND");
    }

    @Test
    void onlyAuthorCanDelete() {
        postStore.put("p1", TestFixtures.publishedPost("p1", "author", 1));
        assertThatThrownBy(() -> service.delete("stranger", "p1"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("FORBIDDEN");
    }

    @Test
    void deleteRemovesPostMediaAndObjectsAndDecrementsCount() {
        storeMedia("m1", MediaStatus.READY);
        var post = TestFixtures.publishedPost("p1", "author", 1);
        post.setMediaIds(List.of("m1"));
        postStore.put("p1", post);

        service.delete("author", "p1");

        verify(posts).delete("p1");
        verify(users).incrementCounter("author", "postCount", -1);
        verify(media).delete("m1");
        verify(storage).deleteAll(List.of("media/author/m1/original", "media/author/m1/display.jpg"));
    }

    @Test
    void listByAuthorPaginatesWithCursor() {
        var p3 = TestFixtures.publishedPost("p3", "author", 300);
        var p2 = TestFixtures.publishedPost("p2", "author", 200);
        var p1 = TestFixtures.publishedPost("p1", "author", 100);
        when(posts.findPublishedByAuthor(eq("author"), eq(null), anyInt())).thenReturn(List.of(p3, p2, p1));

        var page = service.listByAuthor("author", null, 2);

        assertThat(page.items()).extracting(PostDtos.PostResponse::postId).containsExactly("p3", "p2");
        assertThat(Cursor.decode(page.nextCursor())).isEqualTo(new Cursor(200, "p2"));
    }
}
