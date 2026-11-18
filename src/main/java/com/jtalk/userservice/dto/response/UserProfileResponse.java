package com.jtalk.userservice.dto.response;

import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.UUID;

/** A user's full public profile. */
@Serdeable.Serializable
public record UserProfileResponse(
        UUID id,
        String username,
        String displayName,
        String bio,
        String avatarUrl,
        String countryCode,
        String nativeLanguage,
        Instant createdAt) {
}
