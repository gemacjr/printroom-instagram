package com.instagram.media;

import com.instagram.common.ApiException;
import com.instagram.media.MediaDtos.CompleteUploadRequest;
import com.instagram.media.MediaDtos.CompletedPart;
import com.instagram.media.MediaDtos.InitiateUploadRequest;
import com.instagram.media.MediaDtos.UploadType;
import com.instagram.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MediaServiceTest {
    private static final long MB = 1024 * 1024;

    private MediaRepository repo;
    private MediaStorage storage;
    private MediaService service;

    @BeforeEach
    void setUp() {
        repo = mock(MediaRepository.class);
        storage = mock(MediaStorage.class);
        var props = TestFixtures.props();
        service = new MediaService(repo, storage, new MediaUrls(props), props, TestFixtures.CLOCK);
        when(storage.presignPut(anyString(), anyString(), any(Long.class))).thenReturn("https://s3/put");
        when(storage.createMultipartUpload(anyString(), anyString())).thenReturn("upload-1");
        when(storage.presignUploadPart(anyString(), anyString(), anyInt()))
                .thenAnswer(inv -> "https://s3/part/" + inv.getArgument(2));
    }

    @Test
    void smallImageGetsSinglePresignedPut() {
        var resp = service.initiateUpload("u1", new InitiateUploadRequest("image/JPEG", 2 * MB));

        assertThat(resp.uploadType()).isEqualTo(UploadType.SINGLE);
        assertThat(resp.uploadUrl()).isEqualTo("https://s3/put");
        assertThat(resp.mediaType()).isEqualTo(MediaType.IMAGE);
        assertThat(resp.expiresAt()).isEqualTo(TestFixtures.NOW.plusSeconds(900));

        var captor = ArgumentCaptor.forClass(Media.class);
        verify(repo).create(captor.capture());
        Media saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(MediaStatus.PENDING_UPLOAD);
        assertThat(saved.getOriginalKey()).isEqualTo("media/u1/" + resp.mediaId() + "/original");
        assertThat(saved.getContentType()).isEqualTo("image/jpeg");
        // Size and type are pinned into the signature.
        verify(storage).presignPut(saved.getOriginalKey(), "image/jpeg", 2 * MB);
    }

    @Test
    void largeVideoGetsMultipartWithOneUrlPerPart() {
        long size = 250 * MB; // 100MB parts -> 3 parts
        var resp = service.initiateUpload("u1", new InitiateUploadRequest("video/mp4", size));

        assertThat(resp.uploadType()).isEqualTo(UploadType.MULTIPART);
        assertThat(resp.partSize()).isEqualTo(100 * MB);
        assertThat(resp.parts()).extracting(MediaDtos.PartUrl::partNumber).containsExactly(1, 2, 3);
        assertThat(resp.parts().get(2).url()).isEqualTo("https://s3/part/3");

        var captor = ArgumentCaptor.forClass(Media.class);
        verify(repo).create(captor.capture());
        assertThat(captor.getValue().getUploadId()).isEqualTo("upload-1");
        assertThat(captor.getValue().getMediaType()).isEqualTo(MediaType.VIDEO);
    }

    @Test
    void rejectsUnsupportedContentType() {
        assertThatThrownBy(() -> service.initiateUpload("u1", new InitiateUploadRequest("application/pdf", 10L)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("UNSUPPORTED_MEDIA_TYPE");
        verify(repo, never()).create(any());
    }

    @Test
    void enforcesPerTypeSizeLimits() {
        assertThatThrownBy(() -> service.initiateUpload("u1", new InitiateUploadRequest("image/png", 9 * MB)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("MEDIA_TOO_LARGE");
        assertThatThrownBy(() -> service.initiateUpload("u1",
                new InitiateUploadRequest("video/mp4", 4L * 1024 * MB + 1)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("MEDIA_TOO_LARGE");
    }

    @Test
    void partSizeRespectsS3Limits() {
        // Honors the 5MB minimum.
        assertThat(MediaService.partSizeFor(50 * MB, 1 * MB)).isEqualTo(5 * MB);
        // Grows parts to stay within 10,000 parts.
        long huge = 100_000L * MB;
        long part = MediaService.partSizeFor(huge, 5 * MB);
        assertThat(Math.ceilDiv(huge, part)).isLessThanOrEqualTo(10_000);
        // Uses preferred when valid.
        assertThat(MediaService.partSizeFor(4096 * MB, 100 * MB)).isEqualTo(100 * MB);
    }

    @Test
    void completeMultipartSortsPartsAndCallsS3() {
        var media = TestFixtures.media("m1", "u1", MediaStatus.PENDING_UPLOAD);
        media.setUploadId("upload-1");
        when(repo.findById("m1")).thenReturn(Optional.of(media));

        service.completeMultipartUpload("u1", "m1", new CompleteUploadRequest(
                List.of(new CompletedPart(2, "e2"), new CompletedPart(1, "e1"))));

        verify(storage).completeMultipartUpload(eq(media.getOriginalKey()), eq("upload-1"),
                eq(List.of(new CompletedPart(1, "e1"), new CompletedPart(2, "e2"))));
    }

    @Test
    void completeRejectsNonMultipartOrOtherOwner() {
        var single = TestFixtures.media("m1", "u1", MediaStatus.PENDING_UPLOAD);
        when(repo.findById("m1")).thenReturn(Optional.of(single));
        var req = new CompleteUploadRequest(List.of(new CompletedPart(1, "e")));

        assertThatThrownBy(() -> service.completeMultipartUpload("u1", "m1", req))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("UPLOAD_NOT_MULTIPART_PENDING");
        // Other users get a 404, not a 403, so media ids can't be probed.
        assertThatThrownBy(() -> service.completeMultipartUpload("u2", "m1", req))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("NOT_FOUND");
    }

    @Test
    void getReturnsUrlOnlyWhenReady() {
        when(repo.findById("p")).thenReturn(Optional.of(TestFixtures.media("p", "u1", MediaStatus.PENDING_UPLOAD)));
        when(repo.findById("r")).thenReturn(Optional.of(TestFixtures.media("r", "u1", MediaStatus.READY)));

        assertThat(service.get("u1", "p").url()).isNull();
        assertThat(service.get("u1", "r").url()).isEqualTo("https://cdn.example.com/media/u1/r/display.jpg");
    }

    @Test
    void deleteAbortsMultipartAndRemovesRecord() {
        var media = TestFixtures.media("m1", "u1", MediaStatus.PENDING_UPLOAD);
        media.setUploadId("upload-1");
        when(repo.findById("m1")).thenReturn(Optional.of(media));

        service.delete("u1", "m1");

        verify(storage).abortMultipartUpload(media.getOriginalKey(), "upload-1");
        verify(repo).delete("m1");
    }

    @Test
    void deleteRefusesAttachedMedia() {
        var media = TestFixtures.media("m1", "u1", MediaStatus.READY);
        media.setPostId("post-1");
        when(repo.findById("m1")).thenReturn(Optional.of(media));

        assertThatThrownBy(() -> service.delete("u1", "m1"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("MEDIA_ATTACHED");
        verify(repo, times(0)).delete(any());
    }

    @Test
    void mediaKeyParsing() {
        assertThat(Media.mediaIdFromOriginalKey("media/u1/m1/original")).isEqualTo("m1");
        assertThat(Media.mediaIdFromOriginalKey("media/u1/m1/display.jpg")).isNull();
        assertThat(Media.mediaIdFromOriginalKey("other/u1/m1/original")).isNull();
        assertThat(Media.mediaIdFromOriginalKey(null)).isNull();
    }
}
