package com.instagram.post;

import com.instagram.media.MediaType;
import com.instagram.user.UserDtos.UserSummary;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class PostDtos {
    private PostDtos() {}

    public static final int MAX_MEDIA_PER_POST = 10;

    public record CreatePostRequest(
            @Size(max = 2200) String caption,
            @NotEmpty @Size(max = MAX_MEDIA_PER_POST) List<String> mediaIds) {}

    public record MediaItem(String mediaId, MediaType mediaType, String url, Integer width, Integer height) {}

    public record PostResponse(
            String postId,
            UserSummary author,
            String caption,
            PostStatus status,
            List<MediaItem> media,
            long createdAt,
            Long publishedAt) {}
}
