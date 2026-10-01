package com.instagram.feed;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FeedMergerTest {

    @Test
    void interleavesSourcesNewestFirst() {
        var cached = List.of(new FeedEntry("a", 500), new FeedEntry("c", 300));
        var celeb = List.of(new FeedEntry("b", 400), new FeedEntry("d", 100));

        var merged = FeedMerger.merge(List.of(cached, celeb), 10);

        assertThat(merged).extracting(FeedEntry::postId).containsExactly("a", "b", "c", "d");
    }

    @Test
    void truncatesToLimit() {
        var src = List.of(new FeedEntry("a", 3), new FeedEntry("b", 2), new FeedEntry("c", 1));
        assertThat(FeedMerger.merge(List.of(src), 2)).extracting(FeedEntry::postId).containsExactly("a", "b");
    }

    @Test
    void deduplicatesPostsPresentInBothSources() {
        // Author crossed the celebrity threshold after this post was fanned out.
        var cached = List.of(new FeedEntry("x", 200));
        var celeb = List.of(new FeedEntry("x", 200), new FeedEntry("y", 100));

        assertThat(FeedMerger.merge(List.of(cached, celeb), 10))
                .extracting(FeedEntry::postId).containsExactly("x", "y");
    }

    @Test
    void tiesBrokenByPostIdDescendingForStableCursors() {
        var src = List.of(new FeedEntry("a", 100), new FeedEntry("c", 100), new FeedEntry("b", 100));
        assertThat(FeedMerger.merge(List.of(src), 10)).extracting(FeedEntry::postId).containsExactly("c", "b", "a");
    }

    @Test
    void emptySources() {
        assertThat(FeedMerger.merge(List.of(List.of(), List.of()), 10)).isEmpty();
    }
}
