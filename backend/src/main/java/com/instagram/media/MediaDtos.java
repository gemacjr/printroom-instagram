package com.instagram.media;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.util.List;

public final class MediaDtos {
    private MediaDtos() {}

    public enum UploadType { SINGLE, MULTIPART }

    public record InitiateUploadRequest(@NotBlank String contentType, @NotNull @Positive Long sizeBytes) {}

    public record PartUrl(int partNumber, String url) {}

    /**
     * SINGLE: PUT the file to {@code uploadUrl} with the same Content-Type.
     * MULTIPART: PUT each {@code partSize} slice to its part URL, collect the ETag headers, then call
     * {@code POST /api/media/{mediaId}/complete}.
     */
    public record InitiateUploadResponse(
            String mediaId,
            MediaType mediaType,
            UploadType uploadType,
            String uploadUrl,
            Long partSize,
            List<PartUrl> parts,
            Instant expiresAt) {}

    public record CompletedPart(@Min(1) int partNumber, @NotBlank String eTag) {}

    public record CompleteUploadRequest(@NotEmpty List<@Valid CompletedPart> parts) {}

    public record MediaResponse(
            String mediaId,
            MediaType mediaType,
            MediaStatus status,
            String contentType,
            long sizeBytes,
            String url,
            Integer width,
            Integer height,
            String postId,
            String failureReason) {}
}
