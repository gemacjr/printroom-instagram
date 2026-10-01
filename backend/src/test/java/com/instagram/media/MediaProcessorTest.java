package com.instagram.media;

import com.instagram.support.TestFixtures;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MediaProcessorTest {
    private final MediaStorage storage = mock(MediaStorage.class);
    private final MediaProcessor processor = new MediaProcessor(storage);

    private static byte[] png(int w, int h, boolean alpha) throws Exception {
        var img = new BufferedImage(w, h, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    void largeImageIsDownscaledTo1080WideJpeg() throws Exception {
        var media = TestFixtures.media("m1", "u1", MediaStatus.PENDING_UPLOAD);
        media.setContentType("image/png");
        when(storage.getBytes(media.getOriginalKey())).thenReturn(png(2160, 1440, true));

        var result = processor.process(media);

        assertThat(result.displayKey()).isEqualTo("media/u1/m1/display.jpg");
        assertThat(result.width()).isEqualTo(1080);
        assertThat(result.height()).isEqualTo(720);
        var captor = org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(storage).put(eq("media/u1/m1/display.jpg"), captor.capture(), eq("image/jpeg"));
        var written = ImageIO.read(new ByteArrayInputStream(captor.getValue()));
        assertThat(written.getWidth()).isEqualTo(1080);
    }

    @Test
    void smallImageIsNeverUpscaled() throws Exception {
        var media = TestFixtures.media("m1", "u1", MediaStatus.PENDING_UPLOAD);
        when(storage.getBytes(anyString())).thenReturn(png(320, 200, false));

        var result = processor.process(media);

        assertThat(result.width()).isEqualTo(320);
        assertThat(result.height()).isEqualTo(200);
    }

    @Test
    void undecodableBytesAreUnprocessable() {
        var media = TestFixtures.media("m1", "u1", MediaStatus.PENDING_UPLOAD);
        when(storage.getBytes(anyString())).thenReturn("definitely not an image".getBytes());

        assertThatThrownBy(() -> processor.process(media))
                .isInstanceOf(MediaProcessor.UnprocessableMediaException.class);
        verify(storage, never()).put(anyString(), any(), anyString());
    }

    @Test
    void videosPassThroughWithoutDownloading() {
        var media = TestFixtures.media("v1", "u1", MediaStatus.PENDING_UPLOAD);
        media.setMediaType(MediaType.VIDEO);
        media.setContentType("video/mp4");

        var result = processor.process(media);

        assertThat(result.displayKey()).isEqualTo(media.getOriginalKey());
        verify(storage, never()).getBytes(anyString());
    }

    @Test
    void gifsKeepOriginalToPreserveAnimation() {
        var media = TestFixtures.media("g1", "u1", MediaStatus.PENDING_UPLOAD);
        media.setContentType("image/gif");
        assertThat(processor.process(media).displayKey()).isEqualTo(media.getOriginalKey());
        verify(storage, never()).getBytes(anyString());
    }
}
