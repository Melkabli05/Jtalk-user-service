package com.jtalk.userservice.service.impl;

import com.jtalk.userservice.config.ProblemType;
import com.jtalk.userservice.config.UserProperties;
import com.jtalk.userservice.repository.UserRepository;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/** The rules for taking another username: only to one nobody has, and only once in a while. */
@Singleton
@RequiredArgsConstructor
class UsernamePolicy {

    private final UserRepository users;
    private final UserProperties properties;

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
