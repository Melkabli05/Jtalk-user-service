package com.jtalk.userservice.controller;

import com.jtalk.userservice.config.UserConstants;
import com.jtalk.userservice.dto.response.FollowListResponse;
import com.jtalk.userservice.dto.response.SocialStatsResponse;
import com.jtalk.userservice.dto.response.UserSummaryResponse;
import com.jtalk.userservice.service.SocialGraphService;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.annotation.Status;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.validation.Validated;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.UUID;

/** Follows and blocks. Both are idempotent: asking for what is already true succeeds. */
@Controller("/api/v1/users")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.VIRTUAL)
@Validated
@RequiredArgsConstructor
class SocialGraphController {

    private final SocialGraphService graph;

    @Post("/{userId}/follow")
    @Status(HttpStatus.NO_CONTENT)
    void follow(UUID userId, @CurrentUser UUID callerId) {
        graph.follow(callerId, userId);
    }

    @Delete("/{userId}/follow")
    @Status(HttpStatus.NO_CONTENT)
    void unfollow(UUID userId, @CurrentUser UUID callerId) {
        graph.unfollow(callerId, userId);
    }

    /** Newest follow first. {@code page} counts from 0. */
    @Get("/{userId}/followers")
    FollowListResponse followers(UUID userId,
                                 @QueryValue(defaultValue = "0") @Min(0) int page,
                                 @QueryValue(defaultValue = "20") @Min(1) @Max(UserConstants.MAX_PAGE_SIZE) int size) {
        return graph.followers(userId, page, size);
    }

    @Get("/{userId}/following")
    FollowListResponse following(UUID userId,
                                 @QueryValue(defaultValue = "0") @Min(0) int page,
                                 @QueryValue(defaultValue = "20") @Min(1) @Max(UserConstants.MAX_PAGE_SIZE) int size) {
        return graph.following(userId, page, size);
    }

    @Get("/{userId}/social")
    SocialStatsResponse stats(UUID userId, @CurrentUser UUID callerId) {
        return graph.stats(callerId, userId);
    }

    @Post("/{userId}/block")
    @Status(HttpStatus.NO_CONTENT)
    void block(UUID userId, @CurrentUser UUID callerId) {
        graph.block(callerId, userId);
    }

    @Delete("/{userId}/block")
    @Status(HttpStatus.NO_CONTENT)
    void unblock(UUID userId, @CurrentUser UUID callerId) {
        graph.unblock(callerId, userId);
    }

    @Get("/me/blocks")
    List<UserSummaryResponse> blockedUsers(@CurrentUser UUID callerId) {
        return graph.blockedUsers(callerId);
    }
}
