package com.jtalk.userservice.domain.entity;

import com.jtalk.userservice.config.UserConstants;
import com.jtalk.userservice.domain.enums.UserStatus;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.DateCreated;
import io.micronaut.data.annotation.DateUpdated;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.time.Instant;
import java.util.UUID;

/**
 * A user's identity and public profile. The id is the subject of the user's JWT, so it is assigned, never generated.
 *
 * @param nativeLanguage the primary native language; it always has a NATIVE row in {@code user_languages} too
 */
@MappedEntity(value = "users", schema = UserConstants.SCHEMA)
public record User(
        @Id UUID id,
        String username,
        String displayName,
        @Nullable String bio,
        @Nullable String avatarUrl,
        @Nullable String countryCode,
        String nativeLanguage,
        UserStatus status,
        @DateCreated Instant createdAt,
        @DateUpdated Instant updatedAt) {

    /** A new, unsaved user: the timestamps are filled in by Micronaut Data when it is inserted. */
    public static User register(UUID id, String username, String displayName, @Nullable String bio, @Nullable String avatarUrl,
                                @Nullable String countryCode, String nativeLanguage) {
        return new User(id, username, displayName.strip(), blankToNull(bio), blankToNull(avatarUrl), countryCode, nativeLanguage,
                UserStatus.ACTIVE, null, null);
    }

    /**
     * Applies a partial update. A null argument keeps the current value (a new username is checked and rate-limited by the service, not here); for {@code bio} and {@code avatarUrl}, which are optional,
     * an empty string clears it.
     */
    public User withProfile(@Nullable String newUsername, @Nullable String newDisplayName, @Nullable String newBio, @Nullable String newAvatarUrl,
                            @Nullable String newCountryCode, @Nullable String newNativeLanguage) {
        return new User(id, newUsername == null ? username : newUsername,
                newDisplayName == null ? displayName : newDisplayName.strip(),
                newBio == null ? bio : blankToNull(newBio),
                newAvatarUrl == null ? avatarUrl : blankToNull(newAvatarUrl),
                newCountryCode == null ? countryCode : newCountryCode,
                newNativeLanguage == null ? nativeLanguage : newNativeLanguage,
                status, createdAt, updatedAt);
    }

    /** The same user with this picture, which may be an uploaded one's address. */
    public User withAvatar(String newAvatarUrl) {
        return withProfile(null, null, null, newAvatarUrl, null, null);
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    @Nullable
    private static String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
