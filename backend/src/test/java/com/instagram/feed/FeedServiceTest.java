package com.instagram.feed;

import com.instagram.common.Cursor;
import com.instagram.follow.FollowRepository;
import com.instagram.media.MediaUrls;
import com.instagram.post.Post;
import com.instagram.post.PostAssembler;
import com.instagram.post.PostDtos.PostResponse;
import com.instagram.post.PostRepository;
import com.instagram.post.PostStatus;
import com.instagram.support.TestFixtures;
import com.instagram.user.User;
import com.instagram.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeedServiceTest {
    private FeedCache cache;
    private FollowRepository follows;
    private PostRepository posts;
    private UserRepository users;
    private FeedService service;
    private final Map<String, Post> postStore = new HashMap<>();

    @BeforeEach
    void setUp() {
        cache = mock(FeedCache.class);
        follows = mock(FollowRepository.class);
        posts = mock(PostRepository.class);
        users = mock(UserRepository.class);
        var props = TestFixtures.props();
        service = new FeedService(cache, follows, posts, new PostAssembler(users, new MediaUrls(props)), props);

        when(users.findAllByIds(anyCollection())).thenAnswer(inv -> {
            Map<String, User> out = new HashMap<>();
            for (Object id : (Collection<?>) inv.getArgument(0)) out.put((String) id, TestFixtures.user((String) id));
            return out;
        });
        when(posts.findAllByIds(anyCollection())).thenAnswer(inv -> {
            Map<String, Post> out = new HashMap<>();
            for (Object id : (Collection<?>) inv.getArgument(0)) {
                if (postStore.containsKey(id)) out.put((String) id, postStore.get(id));
            }
            return out;
        });
        when(cache.exists("me")).thenReturn(true);
    }

    private Post post(String id, String author, long ts) {
        Post p = TestFixtures.publishedPost(id, author, ts);
        postStore.put(id, p);
        return p;
    }

    @Test
    void mergesCachedEntriesWithCelebrityPostsNewestFirst() {
        post("a", "friend", 500);
        post("c", "friend", 300);
        Post b = post("b", "celeb", 400);
        Post d = post("d", "celeb", 100);
        when(follows.findAllFolloweeIds("me")).thenReturn(List.of("friend", "celeb"));
        when(cache.filterCelebrities(List.of("friend", "celeb"))).thenReturn(Set.of("celeb"));
        when(cache.page(eq("me"), eq(null), anyInt()))
                .thenReturn(List.of(new FeedEntry("a", 500), new FeedEntry("c", 300)));
        when(posts.findPublishedByAuthor(eq("celeb"), eq(null), anyInt())).thenReturn(List.of(b, d));

        var page = service.getFeed("me", null, 10);

        assertThat(page.items()).extracting(PostResponse::postId).containsExactly("a", "b", "c", "d");
        assertThat(page.items().get(1).author().userId()).isEqualTo("celeb");
        assertThat(page.nextCursor()).isNull();
        verify(cache, never()).initialize(any(), anyList());
    }

    @Test
    void returnsCursorWhenMoreItemsExistAndPassesItDown() {
        post("a", "f", 500);
        post("b", "f", 400);
        post("c", "f", 300);
        when(follows.findAllFolloweeIds("me")).thenReturn(List.of("f"));
        when(cache.filterCelebrities(anyCollection())).thenReturn(Set.of());
        when(cache.page(eq("me"), eq(null), eq(3)))
                .thenReturn(List.of(new FeedEntry("a", 500), new FeedEntry("b", 400), new FeedEntry("c", 300)));

        var first = service.getFeed("me", null, 2);

        assertThat(first.items()).extracting(PostResponse::postId).containsExactly("a", "b");
        Cursor next = Cursor.decode(first.nextCursor());
        assertThat(next).isEqualTo(new Cursor(400, "b"));

        when(cache.page("me", next, 3)).thenReturn(List.of(new FeedEntry("c", 300)));
        var second = service.getFeed("me", first.nextCursor(), 2);
        assertThat(second.items()).extracting(PostResponse::postId).containsExactly("c");
        assertThat(second.nextCursor()).isNull();
    }

    @Test
    void dropsDeletedAndUnpublishedPostsFromCachedFeed() {
        post("live", "f", 300);
        Post processing = post("proc", "f", 200);
        processing.setStatus(PostStatus.PROCESSING);
        when(follows.findAllFolloweeIds("me")).thenReturn(List.of("f"));
        when(cache.filterCelebrities(anyCollection())).thenReturn(Set.of());
        when(cache.page(eq("me"), eq(null), anyInt())).thenReturn(List.of(
                new FeedEntry("deleted", 400), new FeedEntry("live", 300), new FeedEntry("proc", 200)));

        var page = service.getFeed("me", null, 10);

        assertThat(page.items()).extracting(PostResponse::postId).containsExactly("live");
    }

    @Test
    void rebuildsColdFeedFromNonCelebrityFolloweesAndSelf() {
        when(cache.exists("me")).thenReturn(false);
        when(follows.findAllFolloweeIds("me")).thenReturn(List.of("friend", "celeb"));
        when(cache.filterCelebrities(anyCollection())).thenReturn(Set.of("celeb"));
        Post f1 = post("f1", "friend", 200);
        Post mine = post("mine", "me", 300);
        when(posts.findPublishedByAuthor(eq("friend"), eq(null), anyInt())).thenReturn(List.of(f1));
        when(posts.findPublishedByAuthor(eq("me"), eq(null), anyInt())).thenReturn(List.of(mine));
        when(posts.findPublishedByAuthor(eq("celeb"), any(), anyInt())).thenReturn(List.of());
        when(cache.page(eq("me"), eq(null), anyInt())).thenReturn(List.of());

        service.getFeed("me", null, 10);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<FeedEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(cache).initialize(eq("me"), captor.capture());
        assertThat(captor.getValue()).extracting(FeedEntry::postId).containsExactly("mine", "f1");
        // Celebrity posts are never materialized into the cache.
        verify(posts, never()).findPublishedByAuthor(eq("celeb"), eq(null), eq(20));
    }

    @Test
    void clampsPageSize() {
        when(follows.findAllFolloweeIds("me")).thenReturn(List.of());
        when(cache.filterCelebrities(anyCollection())).thenReturn(Set.of());
        when(cache.page(any(), any(), anyInt())).thenReturn(List.of());

        service.getFeed("me", null, 1000);

        verify(cache).page("me", null, 51); // max 50 + 1 look-ahead
    }
}
