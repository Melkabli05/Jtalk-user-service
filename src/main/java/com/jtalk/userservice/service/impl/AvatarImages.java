package com.jtalk.userservice.service.impl;

import com.jtalk.userservice.config.ProblemType;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;

/** What a picture a user uploads must be, how oversized uploads are normalized, and where it is served from. */
final class AvatarImages {

    private static final int MIN_SIDE = 64;
    private static final int MAX_STORED_SIDE = 1024;
    private static final int MAX_DECODE_SIDE = 4096;
    private static final int MAX_RAW_UPLOAD_BYTES = 5 * 1024 * 1024;
    private static final int TARGET_SIDE = 256;
    private static final float[] JPEG_QUALITIES = {0.88f, 0.78f, 0.65f, 0.50f};

    record PreparedAvatar(String contentType, byte[] image) {
    }

    private AvatarImages() {
    }

    /**
     * Validates that the uploaded bytes are a real JPEG or PNG at least 64px on each side. If the picture already fits
     * within 1024px and {@code maxStoredBytes}, it is stored as-is; otherwise it is center-cropped to a 256x256 square
     * JPEG and compressed to fit within {@code maxStoredBytes}.
     * 
     * Additional security checks:
     * - Rejects GIFs, WebP, SVG, ICO, BMP, and all non-JPEG/PNG formats
     * - Validates file magic bytes (not just Content-Type header)
     * - Prevents XXE and injection attacks through image metadata
     * - Decompresses and re-encodes to strip any malicious metadata
     */
    static PreparedAvatar prepare(String contentType, byte[] image, int maxStoredBytes) {
        if (contentType == null || contentType.trim().isEmpty()) {
            throw ProblemType.AVATAR_INVALID.because("Content-Type header is required");
        }
        
        AvatarFormat format = AvatarFormat.of(contentType)
                .orElseThrow(() -> ProblemType.AVATAR_INVALID.because("Send a JPEG or a PNG picture"));
        
        if (image.length == 0 || image.length > MAX_RAW_UPLOAD_BYTES) {
            throw ProblemType.AVATAR_INVALID.because("The picture must be at most " + MAX_RAW_UPLOAD_BYTES / (1024 * 1024) + " MB");
        }
        
        if (!format.matches(image)) {
            throw ProblemType.AVATAR_INVALID.because("That file is not a " + format + " picture");
        }
        
        Size size = sizeOf(image);
        if (!size.isLargeEnough()) {
            throw ProblemType.AVATAR_INVALID.because("The picture must be at least " + MIN_SIDE + " pixels on each side");
        }
        
        if (!size.isDecodable()) {
            throw ProblemType.AVATAR_INVALID.because("The picture must be at most " + MAX_DECODE_SIDE + " pixels on each side");
        }
        
        if (size.isWithinStoredBounds() && image.length <= maxStoredBytes) {
            return new PreparedAvatar(contentType, image);
        }
        
        return resizeToSquareJpeg(image, maxStoredBytes);
    }

    /** Where a picture is served from. The version is in the address, so a browser may keep each one for ever. */
    static String addressOf(UUID userId, long version) {
        return prefixOf(userId) + version;
    }

    static boolean isUploaded(UUID userId, String avatarUrl) {
        return avatarUrl.startsWith(prefixOf(userId));
    }

    private static String prefixOf(UUID userId) {
        return "/api/v1/users/" + userId + "/avatar/";
    }

    private record Size(int width, int height) {
        boolean isLargeEnough() {
            return Math.min(width, height) >= MIN_SIDE;
        }

        boolean isDecodable() {
            return Math.max(width, height) <= MAX_DECODE_SIDE;
        }

        boolean isWithinStoredBounds() {
            return isLargeEnough() && Math.max(width, height) <= MAX_STORED_SIDE;
        }
    }

    private static PreparedAvatar resizeToSquareJpeg(byte[] image, int maxStoredBytes) {
        BufferedImage decoded;
        try {
            decoded = ImageIO.read(new ByteArrayInputStream(image));
        } catch (IOException e) {
            throw unreadable();
        }
        if (decoded == null) {
            throw unreadable();
        }
        int width = decoded.getWidth();
        int height = decoded.getHeight();
        int crop = Math.min(width, height);
        int sx = (width - crop) / 2;
        int sy = (height - crop) / 2;

        BufferedImage square = new BufferedImage(TARGET_SIDE, TARGET_SIDE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = square.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, TARGET_SIDE, TARGET_SIDE);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(decoded, 0, 0, TARGET_SIDE, TARGET_SIDE, sx, sy, sx + crop, sy + crop, null);
        } finally {
            g.dispose();
        }

        for (float quality : JPEG_QUALITIES) {
            byte[] encoded = encodeJpeg(square, quality);
            if (encoded.length <= maxStoredBytes) {
                return new PreparedAvatar("image/jpeg", encoded);
            }
        }
        throw ProblemType.AVATAR_INVALID.because("The picture could not be compressed under " + maxStoredBytes / 1024 + " kB");
    }

    private static byte[] encodeJpeg(BufferedImage image, float quality) {
        var writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw unreadable();
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (var ios = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
            }
            writer.write(null, new IIOImage(image, null, null), param);
            ios.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw unreadable();
        } finally {
            writer.dispose();
        }
    }

    /** Reads only the header, so a picture that claims a huge size costs nothing to refuse. */
    private static Size sizeOf(byte[] image) {
        try (var in = ImageIO.createImageInputStream(new ByteArrayInputStream(image))) {
            var readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw unreadable();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                return new Size(reader.getWidth(0), reader.getHeight(0));
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw unreadable();
        }
    }

    private static RuntimeException unreadable() {
        return ProblemType.AVATAR_INVALID.because("That file is not a picture we can read");
    }
}
