package com.instagram.feed;

import com.instagram.common.Cursor;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Precomputed per-user feeds plus the celebrity registry. Backed by Redis sorted sets in practice;
 * kept as an interface so feed logic is unit-testable without Redis.
 */
public interface FeedCache {

    /** Whether a feed has been materialized for this user (an empty feed still "exists"). */
    boolean exists(String userId);

    /** Replaces the user's feed with {@code entries} (trimmed to the max size) and marks it materialized. */
    void initialize(String userId, List<FeedEntry> entries);

    /**
     * Adds a post to each materialized feed among {@code userIds}. Users without a materialized feed are
     * skipped — their feed will be rebuilt from DynamoDB on next read (fan-out-on-read fallback).
     */
    void addToExistingFeeds(Collection<String> userIds, FeedEntry entry);

    /** Adds several entries to a single user's feed, if materialized (used for follow backfill). */
    void addAll(String userId, List<FeedEntry> entries);

    void removeAll(String userId, Collection<String> postIds);

    /** Up to {@code count} entries strictly after {@code before} (null = from the top), newest first. */
    List<FeedEntry> page(String userId, Cursor before, int count);

    boolean isCelebrity(String userId);

    Set<String> filterCelebrities(Collection<String> userIds);

    void setCelebrity(String userId, boolean celebrity);
}
