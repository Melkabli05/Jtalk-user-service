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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

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

    @Override
    public void follow(UUID followerId, UUID targetId) {
        if (followerId.equals(targetId)) {
            throw ProblemType.CANNOT_FOLLOW_SELF.because("You cannot follow yourself");
        }
        // The common repeat (a double tap, a retry) is this one read and nothing else. A block removes the follow, so a set that holds it
        // proves no block exists, and the follow could not have been made without both users.
        SocialGraphRedisRepository.Membership following = graph.membership(followerId, GraphDirection.FOLLOWING, targetId);
        if (following.member()) {
            return;
        }
        User target = requireBoth(followerId, targetId).get(targetId);
        if (!target.isActive()) {
            throw ProblemType.USER_NOT_FOUND.because("User " + targetId + " does not exist");
        }
        if (blocks.existsBetween(followerId, targetId)) {
            throw ProblemType.FOLLOW_NOT_ALLOWED.because("You cannot follow this user");
        }

        if (!following.loaded()) {
            ensureLoaded(followerId, GraphDirection.FOLLOWING);
            following = graph.membership(followerId, GraphDirection.FOLLOWING, targetId);
            if (following.member()) {
                return;   // the set was cold, so only now can it say the follow already exists
            }
        }
        if (following.size() >= properties.maxFollowing()) {
            throw ProblemType.FOLLOW_LIMIT_REACHED.because("You can follow at most " + properties.maxFollowing() + " accounts");
        }

        Instant now = Instant.now();
        follows.follow(followerId, targetId, now);
        try {
            graph.recordFollow(followerId, targetId, now);
        } catch (RuntimeException e) {
            log.warn("Follow {} -> {} saved, but Redis could not record it: dropping both users' cached graphs", followerId, targetId, e);
            dropCachedGraphs(followerId, targetId);
        }
    }

    @Override
    public void unfollow(UUID followerId, UUID targetId) {
        follows.unfollow(followerId, targetId);
        try {
            graph.recordUnfollow(followerId, targetId);
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
        // One round trip answers everything for a user whose sets are cached. A loaded set also proves the user exists (sets are only
        // built for users that do), so the existence check and the rebuild wait for the cold read.
        SocialGraphRedisRepository.Stats stats = graph.stats(userId, viewerId);
        if (!stats.loaded()) {
            userService.getRequiredUser(userId);
            ensureLoaded(userId, GraphDirection.FOLLOWERS);
            ensureLoaded(userId, GraphDirection.FOLLOWING);
            stats = graph.stats(userId, viewerId);
        }
        boolean self = viewerId.equals(userId);
        return new SocialStatsResponse(stats.followers(), stats.following(), !self && stats.followedByViewer(), !self && stats.followsViewer());
    }

    @Override
    @Transactional
    public void block(UUID blockerId, UUID targetId) {
        if (blockerId.equals(targetId)) {
            throw ProblemType.CANNOT_BLOCK_SELF.because("You cannot block yourself");
        }
        requireBoth(blockerId, targetId);
        blocks.block(blockerId, targetId, Instant.now());
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
        SocialGraphRedisRepository.Page found = graph.page(userId, direction, (long) page * size, size);
        if (!found.loaded()) {
            userService.getRequiredUser(userId);
            ensureLoaded(userId, direction);
            found = graph.page(userId, direction, (long) page * size, size);
        }
        return new FollowListResponse(userService.getSummaries(found.ids()), page, size, found.total());
    }

    /** Both users in one cache read (and one query for any the cache lacks); a missing one is the usual 404. */
    private Map<UUID, User> requireBoth(UUID callerId, UUID otherId) {
        Map<UUID, User> found = userService.findUsers(List.of(callerId, otherId)).stream()
                .collect(Collectors.toMap(User::id, Function.identity()));
        if (!found.containsKey(callerId)) {
            throw ProblemType.USER_NOT_FOUND.because("You have no profile yet: create it first");
        }
        if (!found.containsKey(otherId)) {
            throw ProblemType.USER_NOT_FOUND.because("User " + otherId + " does not exist");
        }
        return found;
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
