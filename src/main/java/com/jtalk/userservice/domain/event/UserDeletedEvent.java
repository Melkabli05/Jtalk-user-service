package com.jtalk.userservice.domain.event;

import java.util.Set;
import java.util.UUID;

/**
 * An account was deleted (published inside the transaction). {@code neighbours} are everyone who followed the user or was followed by them:
 * their follower and following counts changed, so after the commit their cached follow graphs are dropped along with the user's own, the
 * cached profile is removed, and the deletion is announced on Redis.
 */
public record UserDeletedEvent(UUID userId, Set<UUID> neighbours) {
}
