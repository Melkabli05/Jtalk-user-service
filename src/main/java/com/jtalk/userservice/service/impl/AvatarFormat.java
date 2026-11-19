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

    /** Whether the file really is this kind of picture, whatever its sender called it. */
    boolean matches(byte[] image) {
        return image.length >= signature.length && Arrays.equals(image, 0, signature.length, signature, 0, signature.length);
    }
}
