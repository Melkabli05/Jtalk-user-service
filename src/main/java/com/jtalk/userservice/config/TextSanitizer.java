package com.jtalk.userservice.config;

import io.micronaut.core.annotation.Nullable;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Normalizes user text with Java's {@link Normalizer.Form#NFC} and strips Unicode control ({@code \p{Cc}}),
 * format/bidi-override ({@code \p{Cf}}, preserving ZWJ/ZWNJ), and blank filler characters.
 */
public final class TextSanitizer {

    private static final String FORMAT_OR_FILLER =
            "[\\p{Cf}&&[^\\u200C\\u200D]]|[\\u115F\\u1160\\u180E\\u2800\\u3164\\uFFA0]";
    private static final Pattern UNWANTED_SINGLE_LINE = Pattern.compile("\\p{Cc}|" + FORMAT_OR_FILLER);
    private static final Pattern UNWANTED_MULTI_LINE = Pattern.compile("[\\p{Cc}&&[^\\n]]|" + FORMAT_OR_FILLER);
    private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");
    private static final Pattern VISIBLE = Pattern.compile("[\\p{L}\\p{N}\\p{M}\\p{P}\\p{S}]");

    private TextSanitizer() {
    }

    @Nullable
    public static String cleanSingleLine(@Nullable String text) {
        if (text == null) {
            return null;
        }
        String nfc = Normalizer.normalize(text, Normalizer.Form.NFC);
        return UNWANTED_SINGLE_LINE.matcher(nfc).replaceAll("").strip();
    }

    @Nullable
    public static String cleanMultiLine(@Nullable String text) {
        if (text == null) {
            return null;
        }
        String normalized = Normalizer.normalize(text.replace("\r\n", "\n").replace('\r', '\n'), Normalizer.Form.NFC);
        return BLANK_LINES.matcher(UNWANTED_MULTI_LINE.matcher(normalized).replaceAll("")).replaceAll("\n\n").strip();
    }

    public static boolean isVisible(String text) {
        return VISIBLE.matcher(text).find();
    }
}
