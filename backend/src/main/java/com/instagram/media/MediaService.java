package com.instagram.media;

import com.instagram.common.ApiException;
import com.instagram.common.Ids;
import com.instagram.config.AppProperties;
import com.instagram.media.MediaDtos.CompleteUploadRequest;
import com.instagram.media.MediaDtos.InitiateUploadRequest;
import com.instagram.media.MediaDtos.InitiateUploadResponse;
import com.instagram.media.MediaDtos.MediaResponse;
import com.instagram.media.MediaDtos.PartUrl;
import com.instagram.media.MediaDtos.UploadType;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class MediaService {
    /** S3 hard limits for multipart uploads. */
    static final long S3_MIN_PART_BYTES = 5L * 1024 * 1024;
    static final int S3_MAX_PARTS = 10_000;

    private final MediaRepository repository;
    private final MediaStorage storage;
    private final MediaUrls urls;
    private final AppProperties.Media config;
    private final Clock clock;

    public MediaService(MediaRepository repository, MediaStorage storage, MediaUrls urls,
                        AppProperties props, Clock clock) {
        this.repository = repository;
        this.storage = storage;
        this.urls = urls;
        this.config = props.media();
        this.clock = clock;
    }

    /**
     * Step 1 of the upload flow: record intent and hand back presigned URL(s). Bytes go browser -> S3
     * directly; our servers never proxy media.
     */
    public InitiateUploadResponse initiateUpload(String ownerId, InitiateUploadRequest req) {
        MediaType type = MediaType.fromContentType(req.contentType())
                .orElseThrow(() -> ApiException.badRequest("UNSUPPORTED_MEDIA_TYPE",
                        "Unsupported content type: " + req.contentType()));
        long max = type == MediaType.IMAGE ? config.maxImageBytes() : config.maxVideoBytes();
        if (req.sizeBytes() > max) {
            throw ApiException.badRequest("MEDIA_TOO_LARGE",
                    "%s uploads are limited to %d bytes".formatted(type.name().toLowerCase(), max));
        }

        String mediaId = Ids.newId();
        String key = Media.originalKeyFor(ownerId, mediaId);
        var media = new Media();
        media.setMediaId(mediaId);
        media.setOwnerId(ownerId);
        media.setMediaType(type);
        media.setContentType(req.contentType().toLowerCase());
        media.setSizeBytes(req.sizeBytes());
        media.setOriginalKey(key);
        media.setStatus(MediaStatus.PENDING_UPLOAD);
        media.setCreatedAt(clock.millis());
        var expiresAt = clock.instant().plus(config.presignTtl());

        if (req.sizeBytes() <= config.multipartThresholdBytes()) {
            repository.create(media);
            String url = storage.presignPut(key, media.getContentType(), req.sizeBytes());
            return new InitiateUploadResponse(mediaId, type, UploadType.SINGLE, url, null, null, expiresAt);
        }

        long partSize = partSizeFor(req.sizeBytes(), config.multipartPartBytes());
        int partCount = (int) Math.ceilDiv(req.sizeBytes(), partSize);
        String uploadId = storage.createMultipartUpload(key, media.getContentType());
        media.setUploadId(uploadId);
        repository.create(media);
        List<PartUrl> parts = new ArrayList<>(partCount);
        for (int n = 1; n <= partCount; n++) {
            parts.add(new PartUrl(n, storage.presignUploadPart(key, uploadId, n)));
        }
        return new InitiateUploadResponse(mediaId, type, UploadType.MULTIPART, null, partSize, parts, expiresAt);
    }

    /** Part size honoring S3's 5MB minimum and 10,000-part maximum. */
    static long partSizeFor(long totalBytes, long preferred) {
        long size = Math.max(preferred, S3_MIN_PART_BYTES);
        long minForMaxParts = Math.ceilDiv(totalBytes, S3_MAX_PARTS);
        return Math.max(size, minForMaxParts);
    }

    /** Step 2 (multipart only): stitch the parts together. S3 then emits ObjectCreated. */
    public MediaResponse completeMultipartUpload(String ownerId, String mediaId, CompleteUploadRequest req) {
        Media media = loadOwned(ownerId, mediaId);
        if (media.getStatus() != MediaStatus.PENDING_UPLOAD || media.getUploadId() == null) {
            throw ApiException.conflict("UPLOAD_NOT_MULTIPART_PENDING",
                    "Media is not an in-progress multipart upload");
        }
        var sorted = req.parts().stream().sorted(Comparator.comparingInt(MediaDtos.CompletedPart::partNumber)).toList();
        try {
            storage.completeMultipartUpload(media.getOriginalKey(), media.getUploadId(), sorted);
        } catch (software.amazon.awssdk.services.s3.model.S3Exception e) {
            throw ApiException.badRequest("MULTIPART_COMPLETE_FAILED", e.awsErrorDetails().errorMessage());
        }
        return toResponse(media);
    }

    public MediaResponse get(String ownerId, String mediaId) {
        return toResponse(loadOwned(ownerId, mediaId));
    }

    /** Abandons an upload that hasn't been attached to a post. */
    public void delete(String ownerId, String mediaId) {
        Media media = loadOwned(ownerId, mediaId);
        if (media.getPostId() != null) {
            throw ApiException.conflict("MEDIA_ATTACHED", "Media is attached to a post; delete the post instead");
        }
        if (media.getUploadId() != null) {
            storage.abortMultipartUpload(media.getOriginalKey(), media.getUploadId());
        }
        storage.deleteAll(List.of(media.getOriginalKey(),
                media.getDisplayKey() == null ? media.getOriginalKey() : media.getDisplayKey()));
        repository.delete(mediaId);
    }

    private Media loadOwned(String ownerId, String mediaId) {
        Media media = repository.findById(mediaId).orElseThrow(() -> ApiException.notFound("Media"));
        if (!media.getOwnerId().equals(ownerId)) throw ApiException.notFound("Media");
        return media;
    }

    MediaResponse toResponse(Media m) {
        return new MediaResponse(m.getMediaId(), m.getMediaType(), m.getStatus(), m.getContentType(),
                m.getSizeBytes(), m.getStatus() == MediaStatus.READY ? urls.url(m.getDisplayKey()) : null,
                m.getWidth(), m.getHeight(), m.getPostId(), m.getFailureReason());
    }
}
