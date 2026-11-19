package com.jtalk.userservice.service;

import com.jtalk.userservice.dto.response.AvatarResponse;
import com.jtalk.userservice.dto.response.UserProfileResponse;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.validation.Validated;
import jakarta.validation.constraints.NotNull;

import java.util.Optional;
import java.util.UUID;

/** Pictures users upload for themselves, kept in the database beside the profile. */
@Validated
public interface AvatarService {

    /**
     * Saves the caller's picture and makes it their profile picture. Only JPEG and PNG are accepted, which the bytes must really be (not
     * just the declared type), between 64 and 1024 pixels on each side and no larger than {@code user.max-avatar-bytes}; anything else
     * is a 422. Returns the profile as it is now, with the new address.
     */
    UserProfileResponse upload(@NonNull UUID userId, @NotNull String contentType, byte @NonNull [] image);

    /** The user's uploaded picture, if they have one. */
    Optional<AvatarResponse> find(@NonNull UUID userId);
}
