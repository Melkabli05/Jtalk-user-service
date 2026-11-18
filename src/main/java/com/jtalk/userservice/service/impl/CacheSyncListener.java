package com.jtalk.userservice.service.impl;

import com.jtalk.userservice.domain.event.FollowGraphInvalidated;
import com.jtalk.userservice.domain.event.UserProfileUpdatedEvent;
import com.jtalk.userservice.repository.SocialGraphRedisRepository;
import com.jtalk.userservice.repository.UserRedisRepository;
import io.micronaut.transaction.annotation.TransactionalEventListener;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Brings Redis in line with what a transaction just committed. These run after the commit, so a write that rolls back never touches
 * Redis, and no Redis call holds a database connection. A Redis failure here is logged, never thrown: the change is already stored in the
 * source of truth, and the cache heals when its entry expires.
 */
@Singleton
@Slf4j
@RequiredArgsConstructor
class CacheSyncListener {

    private final UserRedisRepository profiles;
    private final SocialGraphRedisRepository graph;

    @TransactionalEventListener
    void onProfileUpdated(UserProfileUpdatedEvent event) {
        try {
            profiles.put(event.user());
            profiles.announceUpdated(event.user());
        } catch (RuntimeException e) {
            log.warn("Profile {} saved, but its cached copy could not be replaced", event.user().id(), e);
        }
    }

    @TransactionalEventListener
    void onGraphInvalidated(FollowGraphInvalidated event) {
        try {
            graph.evict(event.userIds());
        } catch (RuntimeException e) {
            log.warn("Could not drop the cached follow graphs of {}", event.userIds(), e);
        }
    }
}
