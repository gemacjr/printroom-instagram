package com.instagram.post;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;

import java.util.List;

/**
 * Posts table: PK = postId.
 * GSI author-published-index: PK = authorId, SK = publishedAt. It is a *sparse* index — publishedAt is
 * only set once the post is PUBLISHED — so processing/failed posts never appear in profile timelines
 * or celebrity feed merges.
 */
@DynamoDbBean
public class Post {
    public static final String AUTHOR_INDEX = "author-published-index";

    private String postId;
    private String authorId;
    private String caption;
    private PostStatus status;
    private List<String> mediaIds;
    private List<PostMedia> media;
    private long createdAt;
    private Long publishedAt;

    @DynamoDbPartitionKey
    public String getPostId() { return postId; }
    public void setPostId(String postId) { this.postId = postId; }

    @DynamoDbSecondaryPartitionKey(indexNames = AUTHOR_INDEX)
    public String getAuthorId() { return authorId; }
    public void setAuthorId(String authorId) { this.authorId = authorId; }

    public String getCaption() { return caption; }
    public void setCaption(String caption) { this.caption = caption; }

    public PostStatus getStatus() { return status; }
    public void setStatus(PostStatus status) { this.status = status; }

    public List<String> getMediaIds() { return mediaIds; }
    public void setMediaIds(List<String> mediaIds) { this.mediaIds = mediaIds; }

    public List<PostMedia> getMedia() { return media; }
    public void setMedia(List<PostMedia> media) { this.media = media; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    @DynamoDbSecondarySortKey(indexNames = AUTHOR_INDEX)
    public Long getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Long publishedAt) { this.publishedAt = publishedAt; }
}
