package com.jtalk.userservice.dto.response;

import io.micronaut.serde.annotation.Serdeable;

import java.util.UUID;

/** Just enough to draw a user in a list or a room: name, avatar, where they are from and what they speak. */
@Serdeable.Serializable
public record UserSummaryResponse(
        UUID id,
        String username,
        String displayName,
        String avatarUrl,
        String countryCode,
        String nativeLanguage) {
}
