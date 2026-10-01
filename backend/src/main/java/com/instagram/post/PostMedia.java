package com.instagram.post;

import com.instagram.media.MediaType;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;

/** Media descriptor denormalized onto the post at publish time so feed reads need no media lookups. */
@DynamoDbBean
public class PostMedia {
    private String mediaId;
    private MediaType mediaType;
    private String key;
    private Integer width;
    private Integer height;

    public PostMedia() {}

    public PostMedia(String mediaId, MediaType mediaType, String key, Integer width, Integer height) {
        this.mediaId = mediaId;
        this.mediaType = mediaType;
        this.key = key;
        this.width = width;
        this.height = height;
    }

    public String getMediaId() { return mediaId; }
    public void setMediaId(String mediaId) { this.mediaId = mediaId; }

    public MediaType getMediaType() { return mediaType; }
    public void setMediaType(MediaType mediaType) { this.mediaType = mediaType; }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public Integer getWidth() { return width; }
    public void setWidth(Integer width) { this.width = width; }

    public Integer getHeight() { return height; }
    public void setHeight(Integer height) { this.height = height; }
}
