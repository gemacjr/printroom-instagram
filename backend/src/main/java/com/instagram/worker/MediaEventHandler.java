package com.instagram.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instagram.config.AppProperties;
import com.instagram.media.Media;
import com.instagram.media.MediaProcessor;
import com.instagram.media.MediaRepository;
import com.instagram.media.MediaStatus;
import com.instagram.media.MediaType;
import com.instagram.post.PostService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Handles S3 ObjectCreated notifications for uploaded originals: validate -> process -> READY, then nudge
 * the owning post to publish. Idempotent: media not in PENDING_UPLOAD is ignored.
 */
@Component
public class MediaEventHandler {
    private static final Logger log = LoggerFactory.getLogger(MediaEventHandler.class);

    private final ObjectMapper mapper;
    private final MediaRepository mediaRepository;
    private final MediaProcessor processor;
    private final PostService postService;
    private final AppProperties.Media config;

    public MediaEventHandler(ObjectMapper mapper, MediaRepository mediaRepository, MediaProcessor processor,
                             PostService postService, AppProperties props) {
        this.mapper = mapper;
        this.mediaRepository = mediaRepository;
        this.processor = processor;
        this.postService = postService;
        this.config = props.media();
    }

    public void handle(String body) throws Exception {
        JsonNode root = mapper.readTree(body);
        // S3 sends a one-off s3:TestEvent when a notification configuration is created.
        if ("s3:TestEvent".equals(root.path("Event").asText())) return;
        for (JsonNode record : root.path("Records")) {
            if (!record.path("eventName").asText().startsWith("ObjectCreated")) continue;
            String key = URLDecoder.decode(record.path("s3").path("object").path("key").asText(), StandardCharsets.UTF_8);
            long size = record.path("s3").path("object").path("size").asLong();
            onObjectCreated(key, size);
        }
    }

    void onObjectCreated(String key, long size) {
        String mediaId = Media.mediaIdFromOriginalKey(key);
        if (mediaId == null) return;
        Media media = mediaRepository.findById(mediaId).orElse(null);
        if (media == null || media.getStatus() != MediaStatus.PENDING_UPLOAD) return;

        long max = media.getMediaType() == MediaType.IMAGE ? config.maxImageBytes() : config.maxVideoBytes();
        Optional<Media> updated;
        if (size > max) {
            updated = mediaRepository.markFailed(mediaId, "Uploaded file exceeds the size limit");
        } else {
            try {
                var result = processor.process(media);
                updated = mediaRepository.markReady(mediaId, result.displayKey(), result.width(), result.height(), size);
            } catch (MediaProcessor.UnprocessableMediaException e) {
                updated = mediaRepository.markFailed(mediaId, e.getMessage());
            }
        }
        updated.ifPresent(m -> {
            log.info("Media {} -> {}", mediaId, m.getStatus());
            if (m.getPostId() != null) postService.tryPublish(m.getPostId());
        });
    }
}
