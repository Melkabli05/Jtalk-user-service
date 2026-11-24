package com.jtalk.userservice.controller;

import com.jtalk.userservice.config.UserConstants;
import com.jtalk.userservice.dto.request.AddLanguageRequest;
import com.jtalk.userservice.dto.request.BatchProfilesRequest;
import com.jtalk.userservice.dto.request.CreateProfileRequest;
import com.jtalk.userservice.dto.request.UpdateProfileRequest;
import com.jtalk.userservice.dto.response.LanguageMatrixResponse;
import com.jtalk.userservice.dto.response.UserPageResponse;
import com.jtalk.userservice.dto.response.UserProfileResponse;
import com.jtalk.userservice.dto.response.DiscoveredUserResponse;
import com.jtalk.userservice.dto.response.UserSummaryResponse;
import com.jtalk.userservice.service.LanguageService;
import com.jtalk.userservice.service.UserService;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Patch;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.annotation.Status;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.validation.Validated;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Profiles, the language matrix and discovery. {@code me} is the caller, taken from the verified token. */
@Controller("/api/v1/users")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.VIRTUAL)
@Validated
@RequiredArgsConstructor
class UserProfileController {

    private final UserService users;
    private final LanguageService languages;

    @Post("/me")
    HttpResponse<UserProfileResponse> createProfile(@CurrentUser UUID userId, @Body @Valid CreateProfileRequest request) {
        UserProfileResponse profile = users.createProfile(userId, request);
        return HttpResponse.created(profile, URI.create("/api/v1/users/" + profile.id()));
    }

    @Get("/me")
    UserProfileResponse getMyProfile(@CurrentUser UUID userId) {
        return users.getProfile(userId);
    }

    @Patch("/me")
    UserProfileResponse updateMyProfile(@CurrentUser UUID userId, @Body @Valid UpdateProfileRequest request) {
        return users.updateProfile(userId, request);
    }

    /** Deletes the caller's account and everything that hangs off it. Idempotent. */
    @Delete("/me")
    @Status(HttpStatus.NO_CONTENT)
    void deleteMyAccount(@CurrentUser UUID userId) {
        users.deleteAccount(userId);
    }

    @Get("/{userId}")
    UserProfileResponse getProfile(UUID userId) {
        return users.getProfile(userId);
    }

    /** Resolves a whole room at once. A POST because up to 500 ids do not fit in a URL; it reads nothing and changes nothing. */
    @Post("/batch")
    List<UserSummaryResponse> getProfiles(@Body @Valid BatchProfilesRequest request) {
        return users.getSummaries(request.ids());
    }

    @Get("/search")
    List<DiscoveredUserResponse> search(@CurrentUser UUID userId,
                                     @QueryValue @NotBlank @Size(max = 50) String q,
                                     @QueryValue(defaultValue = "20") @Min(1) @Max(UserConstants.MAX_SEARCH_RESULTS) int size) {
        return users.search(userId, q, size);
    }

    /**
     * Language-exchange partners. With no parameters: native in what you are learning and learning what you speak. Keyset pagination:
     * pass the last user's {@code createdAt} and {@code id} as {@code afterCreatedAt} and {@code afterId}.
     */
    @Get("/matchmaking")
    UserPageResponse matchmaking(@CurrentUser UUID userId,
                                 @Nullable @QueryValue @Pattern(regexp = UserConstants.LANGUAGE_PATTERN) String nativeLang,
                                 @Nullable @QueryValue @Pattern(regexp = UserConstants.LANGUAGE_PATTERN) String learningLang,
                                 @QueryValue(defaultValue = "20") @Min(1) @Max(UserConstants.MAX_PAGE_SIZE) int size,
                                 @Nullable @QueryValue Instant afterCreatedAt,
                                 @Nullable @QueryValue UUID afterId) {
        return languages.findPartners(userId, nativeLang, learningLang, size, afterCreatedAt, afterId);
    }

    @Get("/me/languages")
    LanguageMatrixResponse getMyLanguages(@CurrentUser UUID userId) {
        return languages.getLanguages(userId);
    }

    @Get("/{userId}/languages")
    LanguageMatrixResponse getLanguages(UUID userId) {
        return languages.getLanguages(userId);
    }

    @Post("/me/languages")
    LanguageMatrixResponse addLanguage(@CurrentUser UUID userId, @Body @Valid AddLanguageRequest request) {
        return languages.addLanguage(userId, request);
    }

    @Delete("/me/languages/{languageCode}")
    LanguageMatrixResponse removeLanguage(@CurrentUser UUID userId, String languageCode) {
        return languages.removeLanguage(userId, languageCode);
    }
}
