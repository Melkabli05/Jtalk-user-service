package com.jtalk.userservice.service.impl;

import io.micronaut.core.annotation.Nullable;

import java.util.regex.Pattern;

/** Strips control characters, direction overrides, zero-width characters, and blank Unicode fillers from user-supplied text. */
final class TextSanitizer {

    private static final String INVISIBLE_AND_BLANK =
            "[\\u00AD\\u034F\\u061C\\u115F\\u1160\\u180E\\u200B\\u200E-\\u200F\\u202A-\\u202E\\u2060-\\u206F\\u2800\\u3164\\uFEFF\\uFFA0]";
    private static final Pattern UNWANTED_SINGLE_LINE = Pattern.compile("\\p{Cc}|" + INVISIBLE_AND_BLANK);
    private static final Pattern UNWANTED_MULTI_LINE = Pattern.compile("[\\p{Cc}&&[^\\n]]|" + INVISIBLE_AND_BLANK);
    private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");

    private TextSanitizer() {
    }

    @Nullable
    static String cleanSingleLine(@Nullable String text) {
        if (text == null) {
            return null;
        }
        return UNWANTED_SINGLE_LINE.matcher(text).replaceAll("").strip();
    }

    @Nullable
    static String cleanMultiLine(@Nullable String text) {
        if (text == null) {
            return null;
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        return BLANK_LINES.matcher(UNWANTED_MULTI_LINE.matcher(normalized).replaceAll("")).replaceAll("\n\n").strip();
    }
}
