package com.jtalk.userservice.dto.request;

import com.jtalk.userservice.config.UserConstants;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The caller's own profile, created once. The user id is the token's subject, never part of the body.
 *
 * @param username lowercase letters, digits, {@code _} and {@code .}, 3-30 characters; unique
 * @param avatarUrl an https URL
 */
@Serdeable.Deserializable
public record CreateProfileRequest(
        @NotBlank @Pattern(regexp = UserConstants.USERNAME_PATTERN, message = "must be 3-30 lowercase letters, digits, '_' or '.'") String username,
        @NotBlank @Size(max = 50) @Pattern(regexp = UserConstants.NOT_BLANK_PATTERN, message = "must not be blank") String displayName,
        @Nullable @Size(max = 300) String bio,
        @Nullable @Size(max = 500) @Pattern(regexp = UserConstants.AVATAR_PATTERN, message = "must be an https URL") String avatarUrl,
        @NotBlank @Pattern(regexp = UserConstants.COUNTRY_PATTERN, message = "must be an ISO 3166-1 alpha-2 code such as MA") String countryCode,
        @NotBlank @Pattern(regexp = UserConstants.LANGUAGE_PATTERN, message = "must be a language tag such as ar or pt-BR") String nativeLanguage) {
}
