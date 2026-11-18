package com.jtalk.userservice.service;

import com.jtalk.userservice.config.UserConstants;
import com.jtalk.userservice.dto.response.FollowListResponse;
import com.jtalk.userservice.dto.response.SocialStatsResponse;
import com.jtalk.userservice.dto.response.UserSummaryResponse;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.validation.Validated;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.List;
import java.util.UUID;

/** Follows and blocks. PostgreSQL is the source of truth; the sets in Redis answer the questions a screen asks. */
@Validated
public interface SocialGraphService {

    /** Idempotent. Refused for yourself, for a user who is gone or suspended, and when either user blocked the other. */
    void follow(@NonNull UUID followerId, @NonNull UUID targetId);

    /** Idempotent. */
    void unfollow(@NonNull UUID followerId, @NonNull UUID targetId);

    FollowListResponse followers(@NonNull UUID userId, @Min(0) int page, @Min(1) @Max(UserConstants.MAX_PAGE_SIZE) int size);

    FollowListResponse following(@NonNull UUID userId, @Min(0) int page, @Min(1) @Max(UserConstants.MAX_PAGE_SIZE) int size);

    /** Follower and following counts of {@code userId}, and how {@code viewerId} relates to them. */
    SocialStatsResponse stats(@NonNull UUID viewerId, @NonNull UUID userId);

    /** Idempotent. Also removes any follow between the two, in both directions. */
    void block(@NonNull UUID blockerId, @NonNull UUID targetId);

    /** Idempotent. */
    void unblock(@NonNull UUID blockerId, @NonNull UUID targetId);

    List<UserSummaryResponse> blockedUsers(@NonNull UUID blockerId);
}
