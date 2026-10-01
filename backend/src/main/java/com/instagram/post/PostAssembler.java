package com.instagram.post;

import com.instagram.media.MediaUrls;
import com.instagram.post.PostDtos.MediaItem;
import com.instagram.post.PostDtos.PostResponse;
import com.instagram.user.User;
import com.instagram.user.UserDtos.UserSummary;
import com.instagram.user.UserRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Hydrates posts with author summaries (one batch read) and CDN media URLs. */
@Component
public class PostAssembler {
    private final UserRepository users;
    private final MediaUrls urls;

    public PostAssembler(UserRepository users, MediaUrls urls) {
        this.users = users;
        this.urls = urls;
    }

    /** Preserves input order; posts whose author no longer exists are dropped. */
    public List<PostResponse> toResponses(Collection<Post> posts) {
        if (posts.isEmpty()) return List.of();
        Map<String, User> authors = users.findAllByIds(posts.stream().map(Post::getAuthorId).toList());
        return posts.stream()
                .map(p -> {
                    User author = authors.get(p.getAuthorId());
                    return author == null ? null : toResponse(p, author);
                })
                .filter(Objects::nonNull)
                .toList();
    }

    public PostResponse toResponse(Post p, User author) {
        List<MediaItem> media = p.getMedia() == null ? List.of() : p.getMedia().stream()
                .map(m -> new MediaItem(m.getMediaId(), m.getMediaType(), urls.url(m.getKey()),
                        m.getWidth(), m.getHeight()))
                .toList();
        return new PostResponse(p.getPostId(), UserSummary.from(author), p.getCaption(), p.getStatus(),
                media, p.getCreatedAt(), p.getPublishedAt());
    }
}
