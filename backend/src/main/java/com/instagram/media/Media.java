package com.instagram.media;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

/** Media table: PK = mediaId. Tracks upload/processing state separately from the post. */
@DynamoDbBean
public class Media {
    private String mediaId;
    private String ownerId;
    private MediaType mediaType;
    private String contentType;
    private long sizeBytes;
    private String originalKey;
    private String displayKey;
    private String uploadId;
    private MediaStatus status;
    private String failureReason;
    private String postId;
    private Integer width;
    private Integer height;
    private long createdAt;

    @DynamoDbPartitionKey
    public String getMediaId() { return mediaId; }
    public void setMediaId(String mediaId) { this.mediaId = mediaId; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public MediaType getMediaType() { return mediaType; }
    public void setMediaType(MediaType mediaType) { this.mediaType = mediaType; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }

    public String getOriginalKey() { return originalKey; }
    public void setOriginalKey(String originalKey) { this.originalKey = originalKey; }

    public String getDisplayKey() { return displayKey; }
    public void setDisplayKey(String displayKey) { this.displayKey = displayKey; }

    public String getUploadId() { return uploadId; }
    public void setUploadId(String uploadId) { this.uploadId = uploadId; }

    public MediaStatus getStatus() { return status; }
    public void setStatus(MediaStatus status) { this.status = status; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

    public String getPostId() { return postId; }
    public void setPostId(String postId) { this.postId = postId; }

    public Integer getWidth() { return width; }
    public void setWidth(Integer width) { this.width = width; }

    public Integer getHeight() { return height; }
    public void setHeight(Integer height) { this.height = height; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public static String originalKeyFor(String ownerId, String mediaId) {
        return "media/" + ownerId + "/" + mediaId + "/original";
    }

    /** Extracts the mediaId from an original object key, or null if the key isn't one. */
    public static String mediaIdFromOriginalKey(String key) {
        if (key == null) return null;
        String[] parts = key.split("/");
        if (parts.length != 4 || !"media".equals(parts[0]) || !"original".equals(parts[3])) return null;
        return parts[2];
    }
}
