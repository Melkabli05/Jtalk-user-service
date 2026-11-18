package com.jtalk.userservice.dto.request;

import com.jtalk.userservice.config.UserConstants;
import com.jtalk.userservice.domain.enums.LanguageType;
import com.jtalk.userservice.domain.enums.ProficiencyLevel;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Lists a language, or changes the level of one already listed.
 *
 * @param proficiencyLevel only for a language being learned; defaults to {@code BEGINNER}, and is ignored for a native one
 */
@Serdeable.Deserializable
public record AddLanguageRequest(
        @NotBlank @Pattern(regexp = UserConstants.LANGUAGE_PATTERN, message = "must be a language tag such as ar or pt-BR") String languageCode,
        @NotNull LanguageType languageType,
        @Nullable ProficiencyLevel proficiencyLevel) {
}
