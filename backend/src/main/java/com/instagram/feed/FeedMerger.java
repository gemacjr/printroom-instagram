package com.instagram.feed;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure merge step of the hybrid feed: combines fan-out-on-write and fan-out-on-read candidates. */
public final class FeedMerger {
    private FeedMerger() {}

    /**
     * Merges candidate lists into a single newest-first list of at most {@code limit} entries,
     * de-duplicating by postId (a post can appear in both the cache and a celebrity query if the author
     * crossed the celebrity threshold after it was fanned out).
     */
    public static List<FeedEntry> merge(Collection<? extends Collection<FeedEntry>> sources, int limit) {
        List<FeedEntry> all = new ArrayList<>();
        sources.forEach(all::addAll);
        all.sort(FeedEntry.NEWEST_FIRST);
        Set<String> seen = new HashSet<>();
        List<FeedEntry> out = new ArrayList<>(Math.min(limit, all.size()));
        for (FeedEntry e : all) {
            if (out.size() >= limit) break;
            if (seen.add(e.postId())) out.add(e);
        }
        return out;
    }
}
