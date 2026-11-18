package com.jtalk.userservice.dto.response;

import io.micronaut.serde.annotation.Serdeable;

/**
 * A user's follow numbers, and how the caller relates to them. Both flags are false when the caller asks about themselves.
 *
 * @param followedByMe the caller follows this user
 * @param followsMe    this user follows the caller
 */
@Serdeable.Serializable
public record SocialStatsResponse(long followerCount, long followingCount, boolean followedByMe, boolean followsMe) {
}
