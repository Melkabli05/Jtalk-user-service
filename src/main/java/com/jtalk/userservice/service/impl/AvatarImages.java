package com.jtalk.userservice.service.impl;

import com.jtalk.userservice.config.ProblemType;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.UUID;

/** What a picture a user uploads must be, and where it is served from. */
final class AvatarImages {

    private static final int MIN_SIDE = 64;
    private static final int MAX_SIDE = 1024;

    private AvatarImages() {
    }

    /** Refuses, with a 422 that says why, anything that is not a JPEG or a PNG of a sensible size. Cheap checks first. */
    static void require(String contentType, byte[] image, int maxBytes) {
        AvatarFormat format = AvatarFormat.of(contentType)
                .orElseThrow(() -> ProblemType.AVATAR_INVALID.because("Send a JPEG or a PNG picture"));
        if (image.length == 0 || image.length > maxBytes) {
            throw ProblemType.AVATAR_INVALID.because("The picture must be at most " + maxBytes / 1024 + " kB");
        }
        if (!format.matches(image)) {
            throw ProblemType.AVATAR_INVALID.because("That file is not a " + format + " picture");
        }
        if (!sizeOf(image).isReasonable()) {
            throw ProblemType.AVATAR_INVALID.because("The picture must be between " + MIN_SIDE + " and " + MAX_SIDE + " pixels on each side");
        }
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
        boolean isReasonable() {
            return Math.min(width, height) >= MIN_SIDE && Math.max(width, height) <= MAX_SIDE;
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
