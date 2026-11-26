package com.jtalk.userservice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A user's full public profile, with the languages they speak and the ones they are learning (empty lists stay in the payload). */
@Serdeable.Serializable
public record UserProfileResponse(
        UUID id,
        String username,
        String displayName,
        String bio,
        String avatarUrl,
        String countryCode,
        String nativeLanguage,
        @JsonInclude(JsonInclude.Include.ALWAYS) List<String> nativeLanguages,
        @JsonInclude(JsonInclude.Include.ALWAYS) List<LanguageEntryResponse> learningLanguages,
        Instant createdAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable SocialStatsResponse socialStats) {

    public UserProfileResponse(
            UUID id,
            String username,
            String displayName,
            String bio,
            String avatarUrl,
            String countryCode,
            String nativeLanguage,
            List<String> nativeLanguages,
            List<LanguageEntryResponse> learningLanguages,
            Instant createdAt) {
        this(id, username, displayName, bio, avatarUrl, countryCode, nativeLanguage,
                nativeLanguages, learningLanguages, createdAt, null);
    }

    public UserProfileResponse withSocialStats(SocialStatsResponse stats) {
        return new UserProfileResponse(id, username, displayName, bio, avatarUrl, countryCode,
                nativeLanguage, nativeLanguages, learningLanguages, createdAt, stats);
    }
}
