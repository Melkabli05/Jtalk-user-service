package com.jtalk.userservice.dto.request;

import com.jtalk.userservice.config.UserConstants;
import com.jtalk.userservice.domain.enums.ProficiencyLevel;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * A language the person is learning, sent together with the profile so that the profile and its languages are written in one step.
 *
 * @param proficiencyLevel defaults to {@code BEGINNER}
 */
@Serdeable.Deserializable
public record LearningLanguageRequest(
        @NotBlank @Pattern(regexp = UserConstants.LANGUAGE_PATTERN, message = "must be a language tag such as ar or pt-BR") String languageCode,
        @Nullable ProficiencyLevel proficiencyLevel) {
}
