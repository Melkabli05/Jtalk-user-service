package com.jtalk.userservice.service;

import com.jtalk.userservice.config.UserConstants;
import com.jtalk.userservice.dto.request.AddLanguageRequest;
import com.jtalk.userservice.dto.response.LanguageMatrixResponse;
import com.jtalk.userservice.dto.response.UserPageResponse;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.validation.Validated;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.UUID;

/** The language matrix of a user, and language-exchange matchmaking on top of it. */
@Validated
public interface LanguageService {

    LanguageMatrixResponse getLanguages(@NonNull UUID userId);

    /** Lists a language for the caller, or changes the level of one already listed as learned. */
    LanguageMatrixResponse addLanguage(@NonNull UUID userId, @Valid @NotNull AddLanguageRequest request);

    /** Removes a language from the caller's list. The primary native language cannot be removed: change it on the profile instead. */
    LanguageMatrixResponse removeLanguage(@NonNull UUID userId,
                                          @NotBlank @Pattern(regexp = UserConstants.LANGUAGE_PATTERN) String languageCode);

    /**
     * Partners for a language exchange: users native in what the caller wants to learn who are learning what the caller speaks, minus
     * anyone blocked either way. {@code nativeLang} defaults to the caller's native language and {@code learningLang} to the first language
     * they are learning. Newest first, keyset-paginated like the voice-room feed.
     */
    UserPageResponse findPartners(@NonNull UUID viewerId, @Nullable String nativeLang, @Nullable String learningLang,
                                  @Min(1) @Max(UserConstants.MAX_PAGE_SIZE) int size,
                                  @Nullable Instant afterCreatedAt, @Nullable UUID afterId);
}
