package com.instagram.follow;

import com.instagram.common.ApiException;
import com.instagram.common.Cursor;
import com.instagram.feed.FanoutService;
import com.instagram.feed.FeedCache;
import com.instagram.feed.FeedEntry;
import com.instagram.follow.FollowRepository.FollowPage;
import com.instagram.post.PostRepository;
import com.instagram.support.TestFixtures;
import com.instagram.user.UserDtos.UserSummary;
import com.instagram.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FollowServiceTest {
    private FollowRepository follows;
    private UserRepository users;
    private PostRepository posts;
    private FeedCache cache;
    private FollowService service;

    @BeforeEach
    void setUp() {
        follows = mock(FollowRepository.class);
        users = mock(UserRepository.class);
        posts = mock(PostRepository.class);
        cache = mock(FeedCache.class);
        var props = TestFixtures.props(1000);
        var fanout = new FanoutService(users, follows, cache, props);
        service = new FollowService(follows, users, posts, cache, fanout, TestFixtures.CLOCK);
    }

    @Test
    void cannotFollowSelf() {
        assertThatThrownBy(() -> service.follow("me", "me"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("CANNOT_FOLLOW_SELF");
    }

    @Test
    void followingUnknownUserIs404() {
        when(users.findById("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.follow("me", "ghost"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("NOT_FOUND");
    }

    @Test
    void followRegularUserBackfillsTheirRecentPosts() {
        when(users.findById("friend")).thenReturn(Optional.of(TestFixtures.user("friend", 10)));
        when(follows.create(eq("me"), eq("friend"), anyLong())).thenReturn(true);
        when(posts.findPublishedByAuthor("friend", null, FollowService.BACKFILL_POSTS)).thenReturn(List.of(
                TestFixtures.publishedPost("p2", "friend", 200), TestFixtures.publishedPost("p1", "friend", 100)));

        var result = service.follow("me", "friend");

        assertThat(result.changed()).isTrue();
        verify(follows).create("me", "friend", TestFixtures.NOW.toEpochMilli());
        verify(cache).setCelebrity("friend", false);
        verify(cache).addAll("me", List.of(new FeedEntry("p2", 200), new FeedEntry("p1", 100)));
    }

    @Test
    void followCelebrityRegistersThemAndSkipsBackfill() {
        when(users.findById("star")).thenReturn(Optional.of(TestFixtures.user("star", 5000)));
        when(follows.create(eq("me"), eq("star"), anyLong())).thenReturn(true);

        service.follow("me", "star");

        verify(cache).setCelebrity("star", true);
        verify(cache, never()).addAll(anyString(), any());
    }

    @Test
    void repeatFollowIsIdempotentNoOp() {
        when(users.findById("friend")).thenReturn(Optional.of(TestFixtures.user("friend")));
        when(follows.create(eq("me"), eq("friend"), anyLong())).thenReturn(false);

        var result = service.follow("me", "friend");

        assertThat(result.changed()).isFalse();
        assertThat(result.following()).isTrue();
        verify(cache, never()).addAll(anyString(), any());
    }

    @Test
    void unfollowRemovesTheirPostsFromMyFeed() {
        when(follows.delete("me", "friend")).thenReturn(true);
        when(users.findById("friend")).thenReturn(Optional.of(TestFixtures.user("friend", 5)));
        when(posts.findPublishedByAuthor(eq("friend"), eq(null), anyInt()))
                .thenReturn(List.of(TestFixtures.publishedPost("p1", "friend", 1)));

        var result = service.unfollow("me", "friend");

        assertThat(result.changed()).isTrue();
        verify(cache).removeAll("me", List.of("p1"));
    }

    @Test
    void unfollowWhenNotFollowingDoesNothing() {
        when(follows.delete("me", "friend")).thenReturn(false);
        assertThat(service.unfollow("me", "friend").changed()).isFalse();
        verify(cache, never()).removeAll(anyString(), any());
    }

    @Test
    void followersPageCarriesKeysetCursor() {
        when(follows.findFollowers("star", null, 2)).thenReturn(new FollowPage(List.of("a", "b"), "b"));
        when(users.findAllByIds(List.of("a", "b")))
                .thenReturn(Map.of("a", TestFixtures.user("a"), "b", TestFixtures.user("b")));

        var page = service.followers("star", null, 2);

        assertThat(page.items()).extracting(UserSummary::userId).containsExactly("a", "b");
        assertThat(Cursor.decode(page.nextCursor()).id()).isEqualTo("b");

        when(follows.findFollowers("star", "b", 2)).thenReturn(new FollowPage(List.of(), null));
        when(users.findAllByIds(List.of())).thenReturn(Map.of());
        assertThat(service.followers("star", page.nextCursor(), 2).nextCursor()).isNull();
    }

    @Test
    void isFollowingIsFalseForSelf() {
        assertThat(service.isFollowing("me", "me")).isFalse();
        verify(follows, never()).exists(anyString(), anyString());
    }
}
