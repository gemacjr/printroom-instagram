package com.instagram.media;

import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Post-upload processing. Images are decoded and re-encoded to a feed-sized JPEG (max 1080px wide,
 * Instagram's display width), which also strips EXIF metadata. Videos are passed through; in production
 * this step would submit an AWS Elemental MediaConvert job to produce adaptive HLS renditions.
 */
@Component
public class MediaProcessor {
    public static final int DISPLAY_MAX_WIDTH = 1080;

    public record Result(String displayKey, Integer width, Integer height) {}

    public static class UnprocessableMediaException extends RuntimeException {
        public UnprocessableMediaException(String message) { super(message); }
    }

    private final MediaStorage storage;

    public MediaProcessor(MediaStorage storage) {
        this.storage = storage;
    }

    public Result process(Media media) {
        if (media.getMediaType() == MediaType.VIDEO) {
            return new Result(media.getOriginalKey(), null, null);
        }
        String ct = media.getContentType();
        if ("image/gif".equals(ct) || "image/webp".equals(ct)) {
            // Keep animation / formats ImageIO can't encode; serve original.
            return new Result(media.getOriginalKey(), null, null);
        }
        byte[] original = storage.getBytes(media.getOriginalKey());
        BufferedImage source = decode(original);
        BufferedImage display = resizeToRgb(source, DISPLAY_MAX_WIDTH);
        String displayKey = media.getOriginalKey().replace("/original", "/display.jpg");
        storage.put(displayKey, encodeJpeg(display), "image/jpeg");
        return new Result(displayKey, display.getWidth(), display.getHeight());
    }

    static BufferedImage decode(byte[] bytes) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img == null) throw new UnprocessableMediaException("File is not a decodable image");
            return img;
        } catch (IOException e) {
            throw new UnprocessableMediaException("Corrupt image: " + e.getMessage());
        }
    }

    /** Scales down (never up) to maxWidth and flattens alpha onto white so it can be JPEG encoded. */
    static BufferedImage resizeToRgb(BufferedImage src, int maxWidth) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w > maxWidth) {
            h = Math.max(1, (int) Math.round(h * (maxWidth / (double) w)));
            w = maxWidth;
        }
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    static byte[] encodeJpeg(BufferedImage img) {
        var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        var baos = new ByteArrayOutputStream();
        try (var ios = new MemoryCacheImageOutputStream(baos)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.85f);
            writer.write(null, new IIOImage(img, null, null), param);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writer.dispose();
        }
        return baos.toByteArray();
    }
}
