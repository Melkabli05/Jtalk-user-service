package com.jtalk.userservice.service;

import com.jtalk.userservice.config.UserConstants;
import com.jtalk.userservice.domain.entity.User;
import com.jtalk.userservice.dto.request.CreateProfileRequest;
import com.jtalk.userservice.dto.request.UpdateProfileRequest;
import com.jtalk.userservice.dto.response.UserProfileResponse;
import com.jtalk.userservice.dto.response.UserSummaryResponse;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.validation.Validated;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Profiles: create, read (cached), batch lookup for rooms, partial update, and typeahead search. */
@Validated
public interface UserService {

    /** Creates the caller's profile; the id is their token's subject. A user has one profile, so a second attempt is a 409. */
    UserProfileResponse createProfile(@NonNull UUID userId, @Valid @NotNull CreateProfileRequest request);

    UserProfileResponse getProfile(@NonNull UUID userId);

    /** The user, read through the cache; a missing or unknown id is the same 404 everywhere. */
    User getRequiredUser(@NonNull UUID userId);

    /**
     * Many users at once, in the order asked, duplicates collapsed and unknown ids left out. The cache is read in one pipelined round
     * trip and everything it lacks is loaded with one query, so a full room costs one of each, not one per participant.
     */
    List<User> findUsers(@NonNull Collection<UUID> userIds);

    List<UserSummaryResponse> getSummaries(@NonNull Collection<UUID> userIds);

    UserProfileResponse updateProfile(@NonNull UUID userId, @Valid @NotNull UpdateProfileRequest request);

    /**
     * Deletes the caller's account for good: the profile, their languages, every follow to or from them and every block they made or
     * received go with it (the database cascades), and the cached copies are dropped. Asking again for an account that is already gone is
     * not an error. They may sign up again afterwards.
     */
    void deleteAccount(@NonNull UUID userId);

    /** Active users whose username or display name starts with {@code query}, never ones who blocked the caller or whom the caller blocked. */
    List<UserSummaryResponse> search(@NonNull UUID viewerId, @NotBlank String query,
                                     @Min(1) @Max(UserConstants.MAX_SEARCH_RESULTS) int size);
}
