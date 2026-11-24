package com.jtalk.userservice.dto.response;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.UUID;

@Serdeable.Serializable
public record DiscoveredUserResponse(UUID id, String username, String displayName, @Nullable String avatarUrl,
                                     @Nullable String countryCode,
                                     String nativeLanguage, boolean followedByMe) {
}
