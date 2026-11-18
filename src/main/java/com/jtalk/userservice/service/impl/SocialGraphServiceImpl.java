package com.jtalk.userservice.service.impl;

import com.jtalk.userservice.config.ProblemType;
import com.jtalk.userservice.config.UserConstants;
import com.jtalk.userservice.config.UserProperties;
import com.jtalk.userservice.domain.entity.User;
import com.jtalk.userservice.domain.enums.GraphDirection;
import com.jtalk.userservice.domain.event.FollowGraphInvalidated;
import com.jtalk.userservice.domain.model.FollowEdge;
import com.jtalk.userservice.dto.response.FollowListResponse;
import com.jtalk.userservice.dto.response.SocialStatsResponse;
import com.jtalk.userservice.dto.response.UserSummaryResponse;
import com.jtalk.userservice.repository.BlockRepository;
import com.jtalk.userservice.repository.FollowRepository;
import com.jtalk.userservice.repository.SocialGraphRedisRepository;
import com.jtalk.userservice.service.SocialGraphService;
import com.jtalk.userservice.service.UserService;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.transaction.annotation.Transactional;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Follows and blocks. A follow is written to PostgreSQL first and then recorded in the Redis sets; if Redis fails after the database
 * commit, the affected sets are dropped so the next read rebuilds them from PostgreSQL instead of serving a set that is missing a follow.
 */
@Singleton
@Slf4j
@RequiredArgsConstructor
class SocialGraphServiceImpl implements SocialGraphService {

    /** Rows read from PostgreSQL per round trip while rebuilding a set. */
    private static final int REBUILD_CHUNK = 1000;
    /** Positions after every real follow: the rebuild reads newest first, from here. */
    private static final Instant END_OF_TIME = Instant.parse("9999-12-31T00:00:00Z");
    private static final UUID MAX_UUID = new UUID(-1L, -1L);

    private final UserService userService;
    private final FollowRepository follows;
    private final BlockRepository blocks;
    private final SocialGraphRedisRepository graph;
    private final ApplicationEventPublisher<FollowGraphInvalidated> invalidated;
    private final UserProperties properties;
    private final Clock clock;

    @Override
    public void follow(UUID followerId, UUID targetId) {
        if (followerId.equals(targetId)) {
            throw ProblemType.CANNOT_FOLLOW_SELF.because("You cannot follow yourself");
        }
        userService.getRequiredUser(followerId);
        if (!userService.getRequiredUser(targetId).isActive()) {
            throw ProblemType.USER_NOT_FOUND.because("User " + targetId + " does not exist");
        }
        if (blocks.existsBetween(followerId, targetId)) {
            throw ProblemType.FOLLOW_NOT_ALLOWED.because("You cannot follow this user");
        }

        ensureLoaded(followerId, GraphDirection.FOLLOWING);
        if (graph.contains(followerId, GraphDirection.FOLLOWING, targetId)) {
            return;   // the common repeat: one ZSCORE, no database
        }
        if (graph.size(followerId, GraphDirection.FOLLOWING) >= properties.maxFollowing()) {
            throw ProblemType.FOLLOW_LIMIT_REACHED.because("You can follow at most " + properties.maxFollowing() + " accounts");
        }

        Instant now = clock.instant();
        follows.follow(followerId, targetId, now);
        try {
            graph.addIfLoaded(followerId, GraphDirection.FOLLOWING, targetId, now);
            graph.addIfLoaded(targetId, GraphDirection.FOLLOWERS, followerId, now);
        } catch (RuntimeException e) {
            log.warn("Follow {} -> {} saved, but Redis could not record it: dropping both users' cached graphs", followerId, targetId, e);
            dropCachedGraphs(followerId, targetId);
        }
    }

    @Override
    public void unfollow(UUID followerId, UUID targetId) {
        follows.unfollow(followerId, targetId);
        try {
            graph.remove(followerId, GraphDirection.FOLLOWING, targetId);
            graph.remove(targetId, GraphDirection.FOLLOWERS, followerId);
        } catch (RuntimeException e) {
            log.warn("Unfollow {} -> {} saved, but Redis could not record it: dropping both users' cached graphs", followerId, targetId, e);
            dropCachedGraphs(followerId, targetId);
        }
    }

    @Override
    public FollowListResponse followers(UUID userId, int page, int size) {
        return list(userId, GraphDirection.FOLLOWERS, page, size);
    }

    @Override
    public FollowListResponse following(UUID userId, int page, int size) {
        return list(userId, GraphDirection.FOLLOWING, page, size);
    }

    @Override
    public SocialStatsResponse stats(UUID viewerId, UUID userId) {
        userService.getRequiredUser(userId);
        ensureLoaded(userId, GraphDirection.FOLLOWERS);
        ensureLoaded(userId, GraphDirection.FOLLOWING);
        boolean self = viewerId.equals(userId);
        // Both flags come from the two sets just loaded: the viewer is in the user's followers if they follow them, and in the user's
        // following if the user follows them back.
        return new SocialStatsResponse(
                graph.size(userId, GraphDirection.FOLLOWERS),
                graph.size(userId, GraphDirection.FOLLOWING),
                !self && graph.contains(userId, GraphDirection.FOLLOWERS, viewerId),
                !self && graph.contains(userId, GraphDirection.FOLLOWING, viewerId));
    }

    @Override
    @Transactional
    public void block(UUID blockerId, UUID targetId) {
        if (blockerId.equals(targetId)) {
            throw ProblemType.CANNOT_BLOCK_SELF.because("You cannot block yourself");
        }
        userService.getRequiredUser(blockerId);
        userService.getRequiredUser(targetId);
        blocks.block(blockerId, targetId, clock.instant());
        follows.deleteBetween(blockerId, targetId);
        invalidated.publishEvent(new FollowGraphInvalidated(Set.of(blockerId, targetId)));
    }

    @Override
    public void unblock(UUID blockerId, UUID targetId) {
        blocks.unblock(blockerId, targetId);
    }

    @Override
    public List<UserSummaryResponse> blockedUsers(UUID blockerId) {
        return userService.getSummaries(blocks.findBlockedIds(blockerId, UserConstants.MAX_BLOCKS_LISTED));
    }

    private FollowListResponse list(UUID userId, GraphDirection direction, int page, int size) {
        userService.getRequiredUser(userId);
        ensureLoaded(userId, direction);
        List<UUID> ids = graph.page(userId, direction, (long) page * size, size);
        return new FollowListResponse(userService.getSummaries(ids), page, size, graph.size(userId, direction));
    }

    /** Rebuilds the set from PostgreSQL if Redis does not hold it (first read, expiry, eviction, a Redis restart). */
    private void ensureLoaded(UUID ownerId, GraphDirection direction) {
        if (graph.isLoaded(ownerId, direction)) {
            return;
        }
        SocialGraphRedisRepository.Rebuild rebuild = graph.startRebuild(ownerId, direction);
        Instant afterCreatedAt = END_OF_TIME;
        UUID afterId = MAX_UUID;
        while (true) {
            List<FollowEdge> chunk = direction == GraphDirection.FOLLOWING
                    ? follows.findFollowingAfter(ownerId, afterCreatedAt, afterId, REBUILD_CHUNK)
                    : follows.findFollowersAfter(ownerId, afterCreatedAt, afterId, REBUILD_CHUNK);
            rebuild.add(chunk);
            if (chunk.size() < REBUILD_CHUNK) {
                break;
            }
            FollowEdge last = chunk.getLast();
            afterCreatedAt = last.createdAt();
            afterId = last.userId();
        }
        rebuild.commit();
    }

    private void dropCachedGraphs(UUID a, UUID b) {
        try {
            graph.evict(Set.of(a, b));
        } catch (RuntimeException e) {
            log.warn("Could not drop the cached graphs of {} and {}; they stay until they expire", a, b, e);
        }
    }
}
