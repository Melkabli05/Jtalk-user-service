package com.jtalk.userservice.service.impl;

import com.jtalk.userservice.config.ProblemType;
import com.jtalk.userservice.config.UserProperties;
import com.jtalk.userservice.repository.UserRepository;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;

/** The rules for taking another username: only to one nobody has, and only once in a while. */
@Singleton
@RequiredArgsConstructor
class UsernamePolicy {

    private static final String SUFFIX_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final UserProperties properties;

    /** Generates a unique lowercase handle derived from {@code displayName} for newly signed-up profiles. */
    String generateUnique(String displayName) {
        String cleaned = displayName == null ? ""
                : displayName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.]", "");
        String base = cleaned.isEmpty() ? "speaker" : cleaned.substring(0, Math.min(cleaned.length(), 18));
        if (base.length() < 3) {
            base = "speaker";
        }
        for (int attempt = 0; attempt < 5; attempt++) {
            String candidate = base + "_" + randomSuffix(6);
            if (!users.existsByUsername(candidate)) {
                return candidate;
            }
        }
        return "speaker_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String randomSuffix(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(SUFFIX_CHARS.charAt(RANDOM.nextInt(SUFFIX_CHARS.length())));
        }
        return sb.toString();
    }

    /** Throws the 422 or the 409 that says why this user may not take {@code newUsername} now. */
    void requireAllowed(UUID userId, String newUsername) {
        users.findUsernameChangedAt(userId).ifPresent(changedAt -> {
            Instant next = changedAt.plus(properties.usernameChangeInterval());
            if (Instant.now().isBefore(next)) {
                throw ProblemType.USERNAME_CHANGE_TOO_SOON.because(
                        "You changed your username recently. You can change it again on " + next.atZone(ZoneOffset.UTC).toLocalDate());
            }
        });
        if (users.existsByUsername(newUsername)) {
            throw taken(newUsername);
        }
    }

    static RuntimeException taken(String username) {
        return ProblemType.USERNAME_TAKEN.because("The username '" + username + "' is taken");
    }
}
