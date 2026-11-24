package com.jtalk.userservice.domain.model;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;

import java.time.Instant;
import java.util.UUID;

@Introspected
public record DiscoveredUser(UUID id, String username, String displayName, @Nullable String avatarUrl,
                             @Nullable String countryCode,
                             String nativeLanguage, Instant createdAt, boolean followedByMe) {
}
