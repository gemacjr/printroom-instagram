package com.instagram.feed;

import com.instagram.follow.FollowRepository;
import com.instagram.support.TestFixtures;
import com.instagram.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FanoutServiceTest {
    private UserRepository users;
    private FollowRepository follows;
    private FeedCache cache;
    private FanoutService service;
    private final FanoutEvent event = new FanoutEvent("p1", "author", 1000);
    private final FeedEntry entry = new FeedEntry("p1", 1000);

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        follows = mock(FollowRepository.class);
        cache = mock(FeedCache.class);
        service = new FanoutService(users, follows, cache, TestFixtures.props(1000));
    }

    @Test
    @SuppressWarnings("unchecked")
    void regularAuthorFansOutToFollowersInBatches() {
        when(users.findById("author")).thenReturn(Optional.of(TestFixtures.user("author", 3)));
        doAnswer(inv -> {
            Consumer<List<String>> consumer = inv.getArgument(2);
            consumer.accept(List.of("f1", "f2"));
            consumer.accept(List.of("f3"));
            return null;
        }).when(follows).forEachFollowerBatch(eq("author"), eq(100), any(Consumer.class));

        int offered = service.fanout(event);

        assertThat(offered).isEqualTo(3);
        verify(cache).addToExistingFeeds(List.of("author"), entry);
        verify(cache).addToExistingFeeds(List.of("f1", "f2"), entry);
        verify(cache).addToExistingFeeds(List.of("f3"), entry);
        verify(cache).setCelebrity("author", false);
    }

    @Test
    void celebrityAuthorIsNotFannedOut() {
        when(users.findById("author")).thenReturn(Optional.of(TestFixtures.user("author", 1000)));

        int offered = service.fanout(event);

        assertThat(offered).isZero();
        verify(cache).setCelebrity("author", true);
        verify(cache).addToExistingFeeds(List.of("author"), entry); // still in their own feed
        verify(follows, never()).forEachFollowerBatch(anyString(), anyInt(), any());
    }

    @Test
    void missingAuthorIsSkipped() {
        when(users.findById("author")).thenReturn(Optional.empty());
        assertThat(service.fanout(event)).isZero();
        verify(cache, never()).addToExistingFeeds(any(), any());
    }

    @Test
    void thresholdIsInclusive() {
        assertThat(service.isCelebrity(TestFixtures.user("x", 999))).isFalse();
        assertThat(service.isCelebrity(TestFixtures.user("x", 1000))).isTrue();
    }
}
