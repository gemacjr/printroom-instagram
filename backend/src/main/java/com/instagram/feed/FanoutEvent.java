package com.instagram.feed;

/** Published to the feed-fanout queue when a post becomes visible. */
public record FanoutEvent(String postId, String authorId, long publishedAt) {}
