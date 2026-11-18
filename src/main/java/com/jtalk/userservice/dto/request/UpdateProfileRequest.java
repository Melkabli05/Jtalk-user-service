package com.jtalk.userservice.dto.request;

import com.jtalk.userservice.config.UserConstants;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A partial update: a field that is absent keeps its value. {@code bio} and {@code avatarUrl} are optional, so an empty string clears
 * them. The username cannot be changed.
 */
@Serdeable.Deserializable
public record UpdateProfileRequest(
        @Nullable @Size(min = 1, max = 50) @Pattern(regexp = UserConstants.NOT_BLANK_PATTERN, message = "must not be blank") String displayName,
        @Nullable @Size(max = 300) String bio,
        @Nullable @Size(max = 500) @Pattern(regexp = UserConstants.AVATAR_PATTERN, message = "must be an https URL") String avatarUrl,
        @Nullable @Pattern(regexp = UserConstants.COUNTRY_PATTERN, message = "must be an ISO 3166-1 alpha-2 code such as MA") String countryCode,
        @Nullable @Pattern(regexp = UserConstants.LANGUAGE_PATTERN, message = "must be a language tag such as ar or pt-BR") String nativeLanguage) {
}
