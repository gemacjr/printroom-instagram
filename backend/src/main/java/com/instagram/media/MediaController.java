package com.instagram.media;

import com.instagram.media.MediaDtos.CompleteUploadRequest;
import com.instagram.media.MediaDtos.InitiateUploadRequest;
import com.instagram.media.MediaDtos.InitiateUploadResponse;
import com.instagram.media.MediaDtos.MediaResponse;
import com.instagram.security.AuthenticatedUser;
import com.instagram.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/media")
@Tag(name = "Media", description = "Direct-to-S3 uploads via presigned URLs")
public class MediaController {
    private final MediaService mediaService;

    public MediaController(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    @PostMapping("/uploads")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Start an upload; returns a presigned PUT URL or multipart part URLs")
    public InitiateUploadResponse initiate(@CurrentUser AuthenticatedUser user,
                                           @Valid @RequestBody InitiateUploadRequest req) {
        return mediaService.initiateUpload(user.userId(), req);
    }

    @PostMapping("/{mediaId}/complete")
    @Operation(summary = "Complete a multipart upload with the part ETags")
    public MediaResponse complete(@CurrentUser AuthenticatedUser user, @PathVariable String mediaId,
                                  @Valid @RequestBody CompleteUploadRequest req) {
        return mediaService.completeMultipartUpload(user.userId(), mediaId, req);
    }

    @GetMapping("/{mediaId}")
    @Operation(summary = "Get upload/processing status")
    public MediaResponse get(@CurrentUser AuthenticatedUser user, @PathVariable String mediaId) {
        return mediaService.get(user.userId(), mediaId);
    }

    @DeleteMapping("/{mediaId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Abort/delete an unattached upload")
    public void delete(@CurrentUser AuthenticatedUser user, @PathVariable String mediaId) {
        mediaService.delete(user.userId(), mediaId);
    }
}
