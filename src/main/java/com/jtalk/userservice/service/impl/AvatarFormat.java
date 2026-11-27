package com.jtalk.userservice.service.impl;

import java.util.Arrays;
import java.util.Optional;

/** The kinds of picture accepted, each with the bytes every file of that kind starts with. */
enum AvatarFormat {
    JPEG("image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),
    PNG("image/png", new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});

    private final String contentType;
    private final byte[] signature;

    AvatarFormat(String contentType, byte[] signature) {
        this.contentType = contentType;
        this.signature = signature;
    }

    static Optional<AvatarFormat> of(String contentType) {
        return Arrays.stream(values()).filter(format -> format.contentType.equals(contentType)).findFirst();
    }

    /** Whether the file really is this kind of picture, whatever its sender called it. Blocks GIFs and other formats. */
    boolean matches(byte[] image) {
        if (image.length >= signature.length && Arrays.equals(image, 0, signature.length, signature, 0, signature.length)) {
            return true;
        }
        
        if (isGif(image)) {
            return false;
        }
        if (isSvg(image)) {
            return false;
        }
        if (isWebP(image)) {
            return false;
        }
        if (isIco(image)) {
            return false;
        }
        if (isBmp(image)) {
            return false;
        }
        
        return false;
    }

    private static boolean isGif(byte[] image) {
        if (image.length < 6) return false;
        return (image[0] == 'G' && image[1] == 'I' && image[2] == 'F' &&
                image[3] == '8' && (image[4] == '7' || image[4] == '9') &&
                image[5] == 'a');
    }

    private static boolean isSvg(byte[] image) {
        if (image.length < 4) return false;
        try {
            String start = new String(image, 0, Math.min(100, image.length), "UTF-8").toLowerCase();
            return start.contains("<svg") || start.contains("<?xml") && start.contains("svg");
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isWebP(byte[] image) {
        if (image.length < 12) return false;
        return image[0] == 'R' && image[1] == 'I' && image[2] == 'F' && image[3] == 'F' &&
               image[8] == 'W' && image[9] == 'E' && image[10] == 'B' && image[11] == 'P';
    }

    private static boolean isIco(byte[] image) {
        if (image.length < 4) return false;
        return image[0] == 0 && image[1] == 0 && image[2] == 1 && image[3] == 0;
    }

    private static boolean isBmp(byte[] image) {
        if (image.length < 2) return false;
        return image[0] == 'B' && image[1] == 'M';
    }
}
