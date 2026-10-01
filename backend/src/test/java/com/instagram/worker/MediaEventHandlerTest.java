package com.instagram.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instagram.media.Media;
import com.instagram.media.MediaProcessor;
import com.instagram.media.MediaRepository;
import com.instagram.media.MediaStatus;
import com.instagram.post.PostService;
import com.instagram.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MediaEventHandlerTest {
    private MediaRepository repo;
    private MediaProcessor processor;
    private PostService postService;
    private MediaEventHandler handler;

    @BeforeEach
    void setUp() {
        repo = mock(MediaRepository.class);
        processor = mock(MediaProcessor.class);
        postService = mock(PostService.class);
        handler = new MediaEventHandler(new ObjectMapper(), repo, processor, postService, TestFixtures.props());
    }

    private static String s3Event(String key, long size) {
        return """
                {"Records":[{"eventName":"ObjectCreated:Put","s3":{"bucket":{"name":"b"},
                "object":{"key":"%s","size":%d}}}]}""".formatted(key, size);
    }

    @Test
    void processesPendingMediaAndNudgesItsPost() throws Exception {
        Media pending = TestFixtures.media("m1", "u1", MediaStatus.PENDING_UPLOAD);
        when(repo.findById("m1")).thenReturn(Optional.of(pending));
        when(processor.process(pending)).thenReturn(new MediaProcessor.Result("media/u1/m1/display.jpg", 1080, 720));
        Media ready = TestFixtures.media("m1", "u1", MediaStatus.READY);
        ready.setPostId("post-1");
        when(repo.markReady("m1", "media/u1/m1/display.jpg", 1080, 720, 5000)).thenReturn(Optional.of(ready));

        handler.handle(s3Event("media/u1/m1/original", 5000));

        verify(postService).tryPublish("post-1");
    }

    @Test
    void readyMediaWithoutPostDoesNotPublish() throws Exception {
        Media pending = TestFixtures.media("m1", "u1", MediaStatus.PENDING_UPLOAD);
        when(repo.findById("m1")).thenReturn(Optional.of(pending));
        when(processor.process(pending)).thenReturn(new MediaProcessor.Result("k", 1, 1));
        when(repo.markReady(anyString(), anyString(), any(), any(), anyLong()))
                .thenReturn(Optional.of(TestFixtures.media("m1", "u1", MediaStatus.READY)));

        handler.handle(s3Event("media/u1/m1/original", 10));

        verify(postService, never()).tryPublish(anyString());
    }

    @Test
    void oversizedUploadIsFailedWithoutProcessing() throws Exception {
        when(repo.findById("m1")).thenReturn(Optional.of(TestFixtures.media("m1", "u1", MediaStatus.PENDING_UPLOAD)));
        Media failed = TestFixtures.media("m1", "u1", MediaStatus.FAILED);
        failed.setPostId("post-1");
        when(repo.markFailed(eq("m1"), contains("size limit"))).thenReturn(Optional.of(failed));

        handler.handle(s3Event("media/u1/m1/original", 9L * 1024 * 1024));

        verify(processor, never()).process(any());
        verify(postService).tryPublish("post-1"); // so the post flips to FAILED
    }

    @Test
    void unprocessableMediaIsMarkedFailed() throws Exception {
        Media pending = TestFixtures.media("m1", "u1", MediaStatus.PENDING_UPLOAD);
        when(repo.findById("m1")).thenReturn(Optional.of(pending));
        when(processor.process(pending)).thenThrow(new MediaProcessor.UnprocessableMediaException("bad image"));
        when(repo.markFailed("m1", "bad image")).thenReturn(Optional.empty());

        handler.handle(s3Event("media/u1/m1/original", 10));

        verify(repo).markFailed("m1", "bad image");
    }

    @Test
    void duplicateEventsForProcessedMediaAreIgnored() throws Exception {
        when(repo.findById("m1")).thenReturn(Optional.of(TestFixtures.media("m1", "u1", MediaStatus.READY)));

        handler.handle(s3Event("media/u1/m1/original", 10));

        verifyNoInteractions(processor);
        verify(repo, never()).markReady(anyString(), anyString(), any(), any(), anyLong());
    }

    @Test
    void ignoresDerivedObjectsAndTestEvents() throws Exception {
        handler.handle(s3Event("media/u1/m1/display.jpg", 10));
        handler.handle("{\"Service\":\"Amazon S3\",\"Event\":\"s3:TestEvent\"}");
        verify(repo, never()).findById(anyString());
    }

    @Test
    void urlDecodesKeys() throws Exception {
        handler.handle(s3Event("media/u%3D1/m1/original", 10));
        verify(repo).findById("m1");
    }
}
