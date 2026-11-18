package com.jtalk.userservice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

/** One page of a follower or following list, newest follow first; {@code total} is the size of the whole list. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Serdeable.Serializable
public record FollowListResponse(
        List<UserSummaryResponse> content,
        int page,
        int size,
        long total) {
}
