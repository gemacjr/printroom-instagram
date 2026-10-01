package com.instagram.feed;

import java.util.Comparator;

/** A (postId, publishedAt) pair — the only thing stored in the precomputed feed. */
public record FeedEntry(String postId, long publishedAt) {
    /** Newest first; postId breaks ties so ordering (and cursors) are deterministic. */
    public static final Comparator<FeedEntry> NEWEST_FIRST =
            Comparator.comparingLong(FeedEntry::publishedAt).reversed()
                    .thenComparing(FeedEntry::postId, Comparator.reverseOrder());
}
