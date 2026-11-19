package com.jtalk.userservice.dto.request;

import com.jtalk.userservice.config.UserConstants;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * A partial update: a field that is absent keeps its value. {@code bio} and {@code avatarUrl} are optional, so an empty string clears
 * them. The username can be changed, but only once in a while (see {@code user.username-change-interval}).
 *
 * <p>{@code learningLanguages} replaces the whole list of languages being learned when it is present (an empty list clears it) and leaves
 * it alone when it is absent. It is applied with the rest, in one transaction: either everything changes or nothing does, and a language
 * can move between native and learning in the same request.
 */
@Serdeable.Deserializable
public record UpdateProfileRequest(
        @Nullable @Size(min = 1, max = 50) @Pattern(regexp = UserConstants.NOT_BLANK_PATTERN, message = "must not be blank") String displayName,
        @Nullable @Size(max = 300) String bio,
        @Nullable @Size(max = 500) @Pattern(regexp = UserConstants.AVATAR_PATTERN, message = "must be an https URL or an app path such as /avatars/a.svg") String avatarUrl,
        @Nullable @Pattern(regexp = UserConstants.COUNTRY_PATTERN, message = "must be an ISO 3166-1 alpha-2 code such as MA") String countryCode,
        @Nullable @Pattern(regexp = UserConstants.LANGUAGE_PATTERN, message = "must be a language tag such as ar or pt-BR") String nativeLanguage,
        @Nullable @Size(max = UserConstants.MAX_LANGUAGES_PER_REQUEST) List<@NotNull @Valid LearningLanguageRequest> learningLanguages,
        @Nullable @Pattern(regexp = UserConstants.USERNAME_PATTERN, message = "must be 3-30 lowercase letters, digits, '_' or '.'") String username) {
}
