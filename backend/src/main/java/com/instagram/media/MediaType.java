package com.instagram.media;

import java.util.Map;
import java.util.Optional;

public enum MediaType {
    IMAGE, VIDEO;

    private static final Map<String, MediaType> ALLOWED = Map.of(
            "image/jpeg", IMAGE,
            "image/png", IMAGE,
            "image/webp", IMAGE,
            "image/gif", IMAGE,
            "video/mp4", VIDEO,
            "video/quicktime", VIDEO,
            "video/webm", VIDEO);

    public static Optional<MediaType> fromContentType(String contentType) {
        if (contentType == null) return Optional.empty();
        return Optional.ofNullable(ALLOWED.get(contentType.toLowerCase()));
    }
}
